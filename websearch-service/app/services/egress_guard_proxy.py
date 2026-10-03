"""Connect-time SSRF check for Chrome: a local SOCKS5 proxy that only dials
the address it has just verified.

Why this exists: the CDP request guard (`browser_request_guard`) decides on a
URL by resolving its hostname in Python, then lets Chrome go; Chrome resolves
the name AGAIN on its own. A DNS server that answers a public address to the
first query and 10.0.0.5 or 169.254.169.254 to the second (DNS rebinding,
TTL 0 or per-query answers) gets a request the guard approved sent to an
internal host.

Chrome is therefore started with `--proxy-server=socks5://127.0.0.1:<port>`
pointing here. With a SOCKS5 proxy Chrome does not resolve hostnames itself:
it hands the NAME to the proxy (remote DNS). This proxy resolves it once
through `crawl_filter.resolve_egress_addresses`, refuses the connection if
any answer is non-public, and otherwise opens the TCP connection itself to
one of the addresses it just checked. The address checked IS the address
connected to, so no second answer can redirect it.

Being a plain TCP tunnel, it also covers what the CDP Fetch guard cannot
see: WebSocket handshakes (`ws://`/`wss://` go through the proxy like any
other connection). Chrome sends some destinations DIRECT whatever the proxy
setting (its implicit bypass rules: localhost, `*.localhost`, 127.0.0.0/8,
[::1], [::ffff:127.0.0.1], 169.254.0.0/16, [fe80::]/10), so a page could
open `ws://127.0.0.1:8093/` or `ws://169.254.169.254/` without either guard
seeing it. `CHROME_EGRESS_ARGS` carries `--proxy-bypass-list=<-loopback>`,
which removes every implicit rule: verified on a real Chrome, all of those
forms then reach this proxy, which refuses them (loopback and link-local
are non-public). UDP (WebRTC, QUIC) does not go through a SOCKS proxy:
Chrome does not use QUIC with a proxy configured, and `CHROME_EGRESS_ARGS`
also sets `--force-webrtc-ip-handling-policy=disable_non_proxied_udp`.

Throughput. Every browser connection goes through here, so:
  - names are resolved on a dedicated thread pool, concurrent lookups of the
    same name share one resolution, and an ALLOWED answer is reused for
    `cache_ttl_s` (the cached addresses are the checked ones, so reuse can
    never reach a host the check refused);
  - dialling races the checked addresses happy-eyeballs style (IPv4 first,
    families interleaved, a new attempt every `happy_eyeballs_delay_s` or as
    soon as the previous one fails, `connect_timeout_s` per address,
    `dial_timeout_s` overall), so one dead address costs a fraction of a
    second, not a full connect timeout;
  - bytes are relayed by asyncio protocols that write straight into the
    peer's transport (no coroutine per chunk), with back-pressure: a side
    stops reading while the other side has `_WRITE_HIGH_WATER` bytes
    unsent.
Chrome does not cap the sockets it opens to a SOCKS5 proxy at 32 (that is
the HTTP/1.1 proxy limit, documented in Chromium's net/docs/proxy.md): Chrome
154 held 200 concurrent tunnels through this proxy in the opt-in real-Chrome
test. No command-line flag changes that limit; the only knob is the
enterprise policy `MaxConnectionsPerProxy`, not needed here.

The proxy runs on its own thread and event loop, so tunnelled page bytes
never compete with the FastAPI loop, and listens on 127.0.0.1 only.
"""

from __future__ import annotations

import asyncio
import ipaddress
import logging
import socket
import struct
import threading
import time
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from typing import Callable, Optional

from app.services.crawl_filter import is_unresolvable_reason, resolve_egress_addresses

logger = logging.getLogger(__name__)

_SOCKS_VERSION = 5
_METHOD_NO_AUTH = 0x00
_METHOD_NONE_ACCEPTABLE = 0xFF
_CMD_CONNECT = 0x01
_ATYP_IPV4 = 0x01
_ATYP_DOMAIN = 0x03
_ATYP_IPV6 = 0x04

# RFC 1928 section 6 reply codes.
REP_SUCCEEDED = 0x00
REP_GENERAL_FAILURE = 0x01
REP_NOT_ALLOWED = 0x02
REP_HOST_UNREACHABLE = 0x04
REP_COMMAND_NOT_SUPPORTED = 0x07
REP_ADDRESS_TYPE_NOT_SUPPORTED = 0x08

# Back-pressure: a side stops reading while its peer has this much unsent.
_WRITE_HIGH_WATER = 1024 * 1024
_WRITE_LOW_WATER = 256 * 1024
# Bytes a client may send before its tunnel is open (a SOCKS request is far
# smaller; Chrome sends nothing else until the reply).
_MAX_PENDING_BYTES = 64 * 1024


def order_for_dial(addresses: list[str]) -> list[str]:
    """IPv4 first, then the families interleaved (RFC 8305 section 4).

    IPv4 first because a host with broken IPv6 routing is far more common
    than the reverse, and interleaving keeps one family's dead addresses
    from delaying the other's.
    """
    v4 = [a for a in addresses if ":" not in a]
    v6 = [a for a in addresses if ":" in a]
    ordered: list[str] = []
    for i in range(max(len(v4), len(v6))):
        if i < len(v4):
            ordered.append(v4[i])
        if i < len(v6):
            ordered.append(v6[i])
    return ordered


class EgressGuardProxy:
    """SOCKS5 CONNECT-only proxy that dials only verified public addresses.

    `resolve(host) -> (addresses, reason)` defaults to
    `crawl_filter.resolve_egress_addresses`; an empty list refuses the
    connection with reply 0x02 ("not allowed by ruleset"), or 0x04 ("host
    unreachable") when the name simply does not resolve.
    """

    def __init__(
        self,
        resolve: Optional[Callable[[str], tuple[list[str], str]]] = None,
        connect_timeout_s: float = 5.0,
        handshake_timeout_s: float = 10.0,
        on_refused: Optional[Callable[[str, int, str], None]] = None,
        dial_timeout_s: float = 10.0,
        happy_eyeballs_delay_s: float = 0.25,
        cache_ttl_s: float = 30.0,
        max_cache_entries: int = 1024,
        resolver_workers: int = 32,
        start_timeout_s: float = 10.0,
    ) -> None:
        self._resolve = resolve or resolve_egress_addresses
        self._connect_timeout_s = connect_timeout_s
        self._handshake_timeout_s = handshake_timeout_s
        self._on_refused = on_refused
        self._dial_timeout_s = dial_timeout_s
        self._happy_eyeballs_delay_s = happy_eyeballs_delay_s
        self._cache_ttl_s = cache_ttl_s
        self._max_cache_entries = max_cache_entries
        self._resolver_workers = resolver_workers
        self._start_timeout_s = start_timeout_s
        # Loop-thread state: only touched from the proxy's event loop.
        self._cache: OrderedDict[str, tuple[float, list[str]]] = OrderedDict()
        self._inflight: dict[str, asyncio.Future] = {}
        self._connections: set[_ClientSide] = set()
        self._lock = threading.Lock()
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._server: Optional[asyncio.base_events.Server] = None
        self._thread: Optional[threading.Thread] = None
        self._executor: Optional[ThreadPoolExecutor] = None
        self._url: str = ""

    @property
    def url(self) -> str:
        """`socks5://127.0.0.1:<port>` once started, "" before."""
        return self._url

    def is_alive(self) -> bool:
        """True while the proxy thread runs its loop and the server listens."""
        thread, loop, server = self._thread, self._loop, self._server
        return bool(
            self._url and thread is not None and thread.is_alive()
            and loop is not None and loop.is_running()
            and server is not None and server.is_serving()
        )

    def start(self) -> str:
        """Start (once) and return the proxy URL to hand Chrome. Raises on failure.

        Blocks up to 10 s while the proxy thread binds: call it off the
        event loop (`asyncio.to_thread`). A proxy found dead is started
        again on a NEW port: browsers pointing at the old URL must be
        restarted (see `egress_guard_alive`).
        """
        with self._lock:
            if self.is_alive():
                return self._url
            if self._thread is not None:
                logger.error("Egress guard proxy %s is dead; starting a new one", self._url)
                self._discard_dead()
            loop = asyncio.new_event_loop()
            executor = ThreadPoolExecutor(
                max_workers=self._resolver_workers, thread_name_prefix="egress-guard-dns",
            )
            ready = threading.Event()
            failure: list[BaseException] = []
            bound: list[asyncio.base_events.Server] = []
            # start() and the thread agree under `handoff` on who owns the
            # loop: once start() gives up waiting, a bind that completes late
            # closes its own server and loop instead of serving an orphan.
            handoff = threading.Lock()
            abandoned = threading.Event()

            def _run() -> None:
                asyncio.set_event_loop(loop)
                try:
                    server = loop.run_until_complete(loop.create_server(
                        lambda: _ClientSide(self), host="127.0.0.1", port=0, backlog=1024,
                    ))
                except BaseException as e:  # noqa: BLE001 - reported to start()
                    failure.append(e)
                    loop.close()
                    ready.set()
                    return
                with handoff:
                    if abandoned.is_set():
                        server.close()
                        loop.close()
                        return
                    bound.append(server)
                    ready.set()
                loop.run_forever()

            self._executor = executor
            thread = threading.Thread(target=_run, name="egress-guard-proxy", daemon=True)
            thread.start()
            ready.wait(timeout=self._start_timeout_s)
            with handoff:
                if not ready.is_set():
                    abandoned.set()
            if abandoned.is_set() or failure or not bound:
                executor.shutdown(wait=False)
                self._executor = None
                reason = failure[:1] or f"no listener after {self._start_timeout_s}s"
                raise RuntimeError(f"egress guard proxy did not start: {reason}")
            self._server = bound[0]
            port = self._server.sockets[0].getsockname()[1]
            self._loop, self._thread = loop, thread
            self._url = f"socks5://127.0.0.1:{port}"
            logger.info("Egress guard proxy listening on %s", self._url)
            return self._url

    def _discard_dead(self) -> None:
        """Drop a dead instance's state (caller holds `_lock`)."""
        loop, server, executor = self._loop, self._server, self._executor
        connections = list(self._connections)
        self._url, self._loop, self._server, self._thread, self._executor = "", None, None, None, None
        if loop is not None and loop.is_running():
            def _shutdown() -> None:
                if server is not None:
                    server.close()
                for connection in connections:
                    connection.abort()
                loop.stop()
            try:
                loop.call_soon_threadsafe(_shutdown)
            except RuntimeError:
                pass
        elif loop is not None and not loop.is_closed():
            _close_on_stopped_loop(loop, server, connections)
        if executor is not None:
            executor.shutdown(wait=False, cancel_futures=True)
        self._connections.clear()
        self._cache.clear()
        self._inflight.clear()

    def stop(self) -> None:
        with self._lock:
            loop, server, thread, executor = self._loop, self._server, self._thread, self._executor
            self._url, self._loop, self._server, self._thread, self._executor = "", None, None, None, None
        if loop is None:
            return

        async def _close() -> None:
            if server is not None:
                server.close()
            for connection in list(self._connections):
                connection.abort()
            current = asyncio.current_task()
            tasks = [t for t in asyncio.all_tasks() if t is not current]
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

        try:
            asyncio.run_coroutine_threadsafe(_close(), loop).result(timeout=5)
        except Exception:  # noqa: BLE001 - best effort at shutdown
            logger.debug("egress guard proxy close failed", exc_info=True)
        loop.call_soon_threadsafe(loop.stop)
        if thread is not None:
            thread.join(timeout=5)
            if not thread.is_alive():
                loop.close()
        if executor is not None:
            executor.shutdown(wait=False, cancel_futures=True)
        self._cache.clear()
        self._inflight.clear()

    # ── Decision ─────────────────────────────────────────────────────────

    def _decide(self, host: str) -> tuple[list[str], str]:
        try:
            return self._resolve(host)
        except Exception as e:  # noqa: BLE001 - any failure must refuse
            return [], f"egress guard error: {type(e).__name__}"

    async def _addresses_for(self, host: str) -> tuple[list[str], str]:
        """Checked addresses for `host` (cached, one lookup per name at a time)."""
        key = host.lower()
        hit = self._cache.get(key)
        if hit is not None:
            if hit[0] > time.monotonic():
                self._cache.move_to_end(key)
                return list(hit[1]), ""
            del self._cache[key]
        future = self._inflight.get(key)
        if future is None:
            future = asyncio.get_running_loop().run_in_executor(self._executor, self._decide, host)
            self._inflight[key] = future
            future.add_done_callback(lambda f, k=key: self._settle(k, f))
        # Shielded: one waiter giving up must not cancel the shared lookup.
        addresses, reason = await asyncio.shield(future)
        return list(addresses), reason

    def _settle(self, key: str, future: asyncio.Future) -> None:
        if self._inflight.get(key) is future:
            del self._inflight[key]
        if future.cancelled() or future.exception() is not None or self._cache_ttl_s <= 0:
            return
        addresses, _reason = future.result()
        if not addresses:
            return  # refusals are never cached: a fixed record is picked up at once
        self._cache[key] = (time.monotonic() + self._cache_ttl_s, list(addresses))
        self._cache.move_to_end(key)
        while len(self._cache) > self._max_cache_entries:
            self._cache.popitem(last=False)

    # ── Dial ─────────────────────────────────────────────────────────────

    async def _dial(self, addresses: list[str], port: int) -> Optional[_Upstream]:
        """Connect to the first reachable checked address (happy eyeballs)."""
        loop = asyncio.get_running_loop()
        attempts: list[asyncio.Task] = []
        winner: Optional[asyncio.Task] = None

        async def _attempt(address: str) -> _Upstream:
            _transport, protocol = await asyncio.wait_for(
                loop.create_connection(_Upstream, address, port),
                timeout=self._connect_timeout_s,
            )
            return protocol

        try:
            async with asyncio.timeout(self._dial_timeout_s):
                pending: set[asyncio.Task] = set()
                candidates = order_for_dial(addresses)
                for index, address in enumerate(candidates):
                    task = loop.create_task(_attempt(address))
                    attempts.append(task)
                    pending.add(task)
                    last = index == len(candidates) - 1
                    winner = await _first_success(
                        pending, None if last else self._happy_eyeballs_delay_s,
                    )
                    if winner is not None:
                        return winner.result()
                return None
        except TimeoutError:
            return None
        finally:
            for task in attempts:
                if task is not winner and not task.done():
                    task.cancel()
            await asyncio.gather(*attempts, return_exceptions=True)
            for task in attempts:
                if task is winner or task.cancelled() or task.exception() is not None:
                    continue
                task.result().close()  # a later success that lost the race


async def _first_success(pending: set[asyncio.Task], timeout: Optional[float]) -> Optional[asyncio.Task]:
    """First task of `pending` to succeed within `timeout` (None: no limit).

    Returns None when the time is up or when every pending attempt failed,
    so the caller starts the next address at once. Finished tasks are
    removed from `pending`.
    """
    loop = asyncio.get_running_loop()
    deadline = None if timeout is None else loop.time() + timeout
    while pending:
        remaining = None if deadline is None else deadline - loop.time()
        if remaining is not None and remaining <= 0:
            return None
        done, _ = await asyncio.wait(pending, timeout=remaining, return_when=asyncio.FIRST_COMPLETED)
        if not done:
            return None
        for task in done:
            pending.discard(task)
        for task in done:
            if not task.cancelled() and task.exception() is None:
                return task
    return None


def _reply(transport: asyncio.BaseTransport, code: int) -> None:
    # Bound address 0.0.0.0:0: Chrome does not use it for CONNECT.
    transport.write(bytes([_SOCKS_VERSION, code, 0x00, _ATYP_IPV4, 0, 0, 0, 0, 0, 0]))


def _tune(transport: asyncio.BaseTransport) -> None:
    transport.set_write_buffer_limits(high=_WRITE_HIGH_WATER, low=_WRITE_LOW_WATER)
    sock = transport.get_extra_info("socket")
    if sock is not None:
        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError:
            pass


class _Side(asyncio.Protocol):
    """One end of a tunnel; relays to `peer` with back-pressure."""

    def __init__(self) -> None:
        self.transport: Optional[asyncio.Transport] = None
        self.peer: Optional[_Side] = None
        # True between asyncio's pause_writing and resume_writing calls:
        # our transport holds more than the high-water mark unsent.
        self.writing_paused = False

    def connection_made(self, transport: asyncio.BaseTransport) -> None:
        self.transport = transport  # type: ignore[assignment]
        _tune(transport)

    def eof_received(self) -> bool:
        # Chrome never half-closes, so the first direction to end ends the
        # tunnel (returning False closes this side; connection_lost closes
        # the peer once its buffered bytes are written).
        return False

    def connection_lost(self, exc: Optional[BaseException]) -> None:
        if self.peer is not None and self.peer.transport is not None:
            self.peer.transport.close()

    # The PEER's writes are stalled while OUR buffered output is above the
    # high-water mark: stop reading from the peer until it drains.
    def pause_writing(self) -> None:
        self.writing_paused = True
        if self.peer is not None and self.peer.transport is not None:
            _pause(self.peer.transport)

    def resume_writing(self) -> None:
        self.writing_paused = False
        if self.peer is not None and self.peer.transport is not None:
            _resume(self.peer.transport)

    def close(self) -> None:
        if self.transport is not None:
            self.transport.close()

    def abort(self) -> None:
        if self.transport is not None:
            self.transport.abort()


def _close_on_stopped_loop(
    loop: asyncio.AbstractEventLoop,
    server: Optional[asyncio.base_events.Server],
    connections: list["_ClientSide"],
) -> None:
    """Release a dead proxy's sockets, then close its loop.

    The loop's thread is gone, so nothing else will ever run it: closing the
    loop alone would leave the listening socket bound and the accepted
    connections open. Close them here and run the loop once on this thread
    so the close callbacks those calls scheduled actually execute.
    """
    try:
        if server is not None:
            server.close()
        for connection in connections:
            connection.abort()
        try:
            asyncio.get_running_loop()
        except RuntimeError:
            loop.run_until_complete(asyncio.sleep(0))
        else:
            logger.warning("Egress guard proxy cleanup ran on an event loop: "
                           "dead connections are closed without their callbacks")
    except Exception:  # noqa: BLE001 - best effort on an already dead proxy
        logger.debug("egress guard proxy dead-loop cleanup failed", exc_info=True)
    finally:
        loop.close()


def _pause(transport: asyncio.Transport) -> None:
    try:
        if not transport.is_closing():
            transport.pause_reading()
    except (RuntimeError, AttributeError):
        pass


def _resume(transport: asyncio.Transport) -> None:
    try:
        if not transport.is_closing():
            transport.resume_reading()
    except (RuntimeError, AttributeError):
        pass


class _Upstream(_Side):
    """The connection the proxy dialled. Bytes the server sends before the
    tunnel is linked (server-first protocols) are held until then."""

    def __init__(self) -> None:
        super().__init__()
        self.early = bytearray()
        self.ended = False

    def data_received(self, data: bytes) -> None:
        if self.peer is not None and self.peer.transport is not None:
            self.peer.transport.write(data)
        else:
            self.early += data
            if len(self.early) > _WRITE_HIGH_WATER and self.transport is not None:
                _pause(self.transport)

    def connection_lost(self, exc: Optional[BaseException]) -> None:
        self.ended = True
        super().connection_lost(exc)


class _ClientSide(_Side):
    """Chrome's connection: the SOCKS5 handshake, then the relay."""

    def __init__(self, proxy: EgressGuardProxy) -> None:
        super().__init__()
        self._proxy = proxy
        self._buffer = bytearray()
        self._greeted = False
        self._opening: Optional[asyncio.Task] = None
        self._timer: Optional[asyncio.TimerHandle] = None
        self._lost = False

    def connection_made(self, transport: asyncio.BaseTransport) -> None:
        super().connection_made(transport)
        self._proxy._connections.add(self)
        self._timer = asyncio.get_running_loop().call_later(
            self._proxy._handshake_timeout_s, self.abort,
        )

    def data_received(self, data: bytes) -> None:
        if self.peer is not None and self.peer.transport is not None:
            self.peer.transport.write(data)
            return
        self._buffer += data
        if len(self._buffer) > _MAX_PENDING_BYTES:
            self.abort()
            return
        if self._opening is None:
            try:
                self._negotiate()
            except (UnicodeError, ValueError):
                self.close()

    def _negotiate(self) -> None:
        buf = self._buffer
        if not self._greeted:
            if len(buf) < 2:
                return
            if buf[0] != _SOCKS_VERSION:
                self.close()
                return
            n_methods = buf[1]
            if len(buf) < 2 + n_methods:
                return
            methods = bytes(buf[2:2 + n_methods])
            del buf[:2 + n_methods]
            if _METHOD_NO_AUTH not in methods:
                self.transport.write(bytes([_SOCKS_VERSION, _METHOD_NONE_ACCEPTABLE]))
                self.close()
                return
            self.transport.write(bytes([_SOCKS_VERSION, _METHOD_NO_AUTH]))
            self._greeted = True

        if len(buf) < 5:
            return
        version, command, _reserved, address_type = buf[0], buf[1], buf[2], buf[3]
        if version != _SOCKS_VERSION:
            self.close()
            return
        if address_type == _ATYP_IPV4:
            address_end = 4 + 4
        elif address_type == _ATYP_IPV6:
            address_end = 4 + 16
        elif address_type == _ATYP_DOMAIN:
            address_end = 5 + buf[4]
        else:
            _reply(self.transport, REP_ADDRESS_TYPE_NOT_SUPPORTED)
            self.close()
            return
        if len(buf) < address_end + 2:
            return
        if address_type == _ATYP_IPV4:
            host = str(ipaddress.IPv4Address(bytes(buf[4:8])))
        elif address_type == _ATYP_IPV6:
            host = str(ipaddress.IPv6Address(bytes(buf[4:20])))
        else:
            # Chrome sends the name already in its ASCII (punycode) form.
            # Kept as is: the "idna" codec refuses valid IDNA 2008 names
            # (xn--fa-hia.de is "faß.de"), and decoding to Unicode would
            # let the resolver re-encode it to a DIFFERENT name (fass.de).
            host = bytes(buf[5:address_end]).decode("ascii")
        (port,) = struct.unpack("!H", bytes(buf[address_end:address_end + 2]))
        del buf[:address_end + 2]
        if command != _CMD_CONNECT:
            # BIND / UDP ASSOCIATE would open paths this guard does not check.
            _reply(self.transport, REP_COMMAND_NOT_SUPPORTED)
            self.close()
            return
        if self._timer is not None:
            self._timer.cancel()
        # Reading goes on while the tunnel opens (into the bounded buffer),
        # so a client that gives up is noticed and its dial cancelled.
        self._opening = asyncio.get_running_loop().create_task(self._open(host, port))

    async def _open(self, host: str, port: int) -> None:
        proxy = self._proxy
        upstream: Optional[_Upstream] = None
        try:
            try:
                addresses, reason = await asyncio.wait_for(
                    proxy._addresses_for(host), timeout=proxy._handshake_timeout_s,
                )
            except TimeoutError:
                logger.debug("Egress guard: resolving %s timed out", host[:200])
                _reply(self.transport, REP_HOST_UNREACHABLE)
                self.close()
                return
            if not addresses and is_unresolvable_reason(reason):
                # Not an SSRF refusal: the same failure as Chrome's own DNS error.
                logger.debug("Egress guard: %s does not resolve", host[:200])
                _reply(self.transport, REP_HOST_UNREACHABLE)
                self.close()
                return
            if not addresses:
                logger.warning("Egress guard refused %s:%d - %s", host[:200], port, reason)
                if proxy._on_refused is not None:
                    try:
                        proxy._on_refused(host, port, reason)
                    except Exception:  # noqa: BLE001 - listener bugs never unblock
                        logger.debug("on_refused listener failed", exc_info=True)
                _reply(self.transport, REP_NOT_ALLOWED)
                self.close()
                return
            upstream = await proxy._dial(addresses, port)
            if self._lost:
                return
            if upstream is None:
                _reply(self.transport, REP_HOST_UNREACHABLE)
                self.close()
                return
            self.peer, upstream.peer = upstream, self
            _reply(self.transport, REP_SUCCEEDED)
            if upstream.early:
                self.transport.write(bytes(upstream.early))
                upstream.early.clear()
            if upstream.ended:
                self.close()
                return
            # The upstream was paused while its early bytes piled up. Resume
            # it only if writing them did not push Chrome's side past the
            # high-water mark: then asyncio paused us (and the upstream with
            # us) and `resume_writing` resumes it once the buffer drains below
            # the low-water mark. Resuming here anyway would undo that
            # back-pressure.
            if upstream.transport is not None and not self.writing_paused:
                _resume(upstream.transport)
            if self._buffer:
                upstream.transport.write(bytes(self._buffer))
                self._buffer.clear()
            upstream = None  # linked: its lifetime now follows the tunnel
        except asyncio.CancelledError:
            self.abort()
            raise
        except Exception:  # noqa: BLE001 - one bad connection never kills the proxy
            logger.debug("Egress guard connection failed", exc_info=True)
            self.close()
        finally:
            if upstream is not None:
                upstream.close()

    def connection_lost(self, exc: Optional[BaseException]) -> None:
        self._lost = True
        if self._timer is not None:
            self._timer.cancel()
        if self._opening is not None and not self._opening.done():
            self._opening.cancel()
        self._proxy._connections.discard(self)
        super().connection_lost(exc)


_shared: Optional[EgressGuardProxy] = None
_shared_lock = threading.Lock()


def ensure_egress_guard() -> str:
    """Start the process-wide egress guard if needed; return its proxy URL.

    Raises when it cannot start: callers must then refuse to launch a
    browser (fail closed).
    """
    global _shared
    with _shared_lock:
        if _shared is None:
            _shared = EgressGuardProxy()
        return _shared.start()


def egress_guard_alive(url: str) -> bool:
    """True while the process-wide egress guard runs and still listens at `url`.

    A browser started with `--proxy-server=<url>` must be restarted when
    this turns False: its proxy is gone (every connection fails) or was
    replaced by one on another port.
    """
    proxy = _shared
    return bool(url) and proxy is not None and proxy.is_alive() and proxy.url == url


def stop_egress_guard() -> None:
    """Stop the process-wide egress guard (application shutdown)."""
    with _shared_lock:
        proxy = _shared
    if proxy is not None:
        proxy.stop()


# Chrome sends loopback and link-local destinations direct, bypassing any
# proxy, unless this subtractive rule removes those implicit bypasses.
CHROME_PROXY_EVERYTHING_ARG = "--proxy-bypass-list=<-loopback>"

# Chrome flags that go with the proxy: nothing bypasses it (not even
# loopback / link-local, which neither the proxy nor the Fetch guard would
# see as WebSockets otherwise) and WebRTC sends no UDP outside it.
CHROME_EGRESS_ARGS = (
    CHROME_PROXY_EVERYTHING_ARG,
    "--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
)
