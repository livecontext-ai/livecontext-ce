package com.apimarketplace.catalog.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client MCP Java pour gerer les connexions aux serveurs MCP
 * Supporte les differents types : LOCAL_MCP, REMOTE_MCP, API_GATEWAY
 */
@Component
@Slf4j
public class McpClient {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final AtomicLong requestIdCounter = new AtomicLong(1);

    public McpClient(RestTemplate restTemplate) {
        this.objectMapper = new ObjectMapper();
        this.restTemplate = restTemplate;
    }

    /**
     * Interface generique pour les connexions MCP
     */
    public interface McpConnection {
        String getId();
        boolean isConnected();
        McpResponse sendRequest(McpRequest request) throws McpException;
        void close();
        String getServerType();
    }

    /**
     * Connexion MCP via processus local (STDIO)
     */
    public static class LocalMcpConnection implements McpConnection {
        private final String connectionId;
        private final Process process;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final ObjectMapper mapper;
        private volatile boolean connected = false;

        public LocalMcpConnection(String connectionId, Process process, ObjectMapper mapper) {
            this.connectionId = connectionId;
            this.process = process;
            this.mapper = mapper;
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        }

        @Override
        public String getId() {
            return connectionId;
        }

        @Override
        public boolean isConnected() {
            return connected && process.isAlive();
        }

        @Override
        public McpResponse sendRequest(McpRequest request) throws McpException {
            if (!isConnected()) {
                throw new McpException("Connexion MCP locale non etablie");
            }

            try {
                // Serialiser et envoyer la requete
                String requestJson = mapper.writeValueAsString(request);
                log.debug("Envoi requete MCP locale: {}", requestJson);
                
                writer.write(requestJson);
                writer.newLine();
                writer.flush();

                // Lire la reponse
                String responseJson = reader.readLine();
                if (responseJson == null) {
                    throw new McpException("Connexion fermee par le serveur MCP");
                }

                log.debug("Reponse MCP locale: {}", responseJson);
                return mapper.readValue(responseJson, McpResponse.class);

            } catch (IOException e) {
                throw new McpException("Erreur de communication MCP locale: " + e.getMessage(), e);
            }
        }

        @Override
        public void close() {
            try {
                if (writer != null) writer.close();
                if (reader != null) reader.close();
                if (process != null) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
                connected = false;
            } catch (Exception e) {
                log.warn("Erreur lors de la fermeture de la connexion MCP locale: {}", e.getMessage());
            }
        }

        @Override
        public String getServerType() {
            return "LOCAL_MCP";
        }

        public void setConnected(boolean connected) {
            this.connected = connected;
        }
    }

    /**
     * Connexion MCP via HTTP distant
     */
    public static class RemoteMcpConnection implements McpConnection {
        private final String connectionId;
        private final String baseUrl;
        private final HttpHeaders headers;
        private final RestTemplate restTemplate;
        private final ObjectMapper mapper;
        private volatile boolean connected = false;

        public RemoteMcpConnection(String connectionId, String baseUrl, HttpHeaders headers, 
                                 RestTemplate restTemplate, ObjectMapper mapper) {
            this.connectionId = connectionId;
            this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            this.headers = headers;
            this.restTemplate = restTemplate;
            this.mapper = mapper;
        }

        @Override
        public String getId() {
            return connectionId;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public McpResponse sendRequest(McpRequest request) throws McpException {
            if (!isConnected()) {
                throw new McpException("Connexion MCP distante non etablie");
            }

            try {
                String endpoint = baseUrl + "/mcp";
                HttpEntity<McpRequest> httpRequest = new HttpEntity<>(request, headers);
                
                log.debug("Envoi requete MCP distante a {}: {}", endpoint, request);
                
                ResponseEntity<McpResponse> response = restTemplate.exchange(
                    endpoint, HttpMethod.POST, httpRequest, McpResponse.class
                );

                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new McpException("Erreur HTTP: " + response.getStatusCode());
                }

                McpResponse mcpResponse = response.getBody();
                log.debug("Reponse MCP distante: {}", mcpResponse);
                return mcpResponse;

            } catch (Exception e) {
                throw new McpException("Erreur de communication MCP distante: " + e.getMessage(), e);
            }
        }

        @Override
        public void close() {
            connected = false;
        }

        @Override
        public String getServerType() {
            return "REMOTE_MCP";
        }

        public void setConnected(boolean connected) {
            this.connected = connected;
        }
    }

    /**
     * Connexion API Gateway (REST classique)
     */
    public static class ApiGatewayConnection implements McpConnection {
        private final String connectionId;
        private final String baseUrl;
        private final HttpHeaders headers;
        private final RestTemplate restTemplate;
        private volatile boolean connected = false;

        public ApiGatewayConnection(String connectionId, String baseUrl, HttpHeaders headers, RestTemplate restTemplate) {
            this.connectionId = connectionId;
            this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            this.headers = headers;
            this.restTemplate = restTemplate;
        }

        @Override
        public String getId() {
            return connectionId;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public McpResponse sendRequest(McpRequest request) throws McpException {
            // Pour API Gateway, on ne fait pas de requetes MCP standard
            // Les appels d'outils utilisent directement les endpoints REST mappes
            throw new McpException("Les API Gateway n'utilisent pas le protocole MCP standard");
        }

        public HttpEntity<Object> createHttpRequest(Object payload) {
            return new HttpEntity<>(payload, headers);
        }

        public String getFullUrl(String endpoint) {
            return baseUrl + (endpoint.startsWith("/") ? endpoint : "/" + endpoint);
        }

        @Override
        public void close() {
            connected = false;
        }

        @Override
        public String getServerType() {
            return "API_GATEWAY";
        }

        public void setConnected(boolean connected) {
            this.connected = connected;
        }

        public RestTemplate getRestTemplate() {
            return restTemplate;
        }

        public HttpHeaders getHeaders() {
            return headers;
        }
    }

    /**
     * Creer une connexion MCP locale via processus
     */
    public LocalMcpConnection createLocalConnection(String serverId, String command, String[] args, 
                                                  String workingDir, Map<String, String> environment) throws McpException {
        try {
            log.info("Creation connexion MCP locale: {} avec commande: {} {}", serverId, command, Arrays.toString(args));

            // Construire la commande
            List<String> fullCommand = new ArrayList<>();
            fullCommand.add(command);
            fullCommand.addAll(Arrays.asList(args));

            ProcessBuilder pb = new ProcessBuilder(fullCommand);
            
            // Repertoire de travail
            if (workingDir != null && !workingDir.isEmpty()) {
                pb.directory(new File(workingDir));
            }
            
            // Variables d'environnement
            if (environment != null) {
                pb.environment().putAll(environment);
            }

            // Rediriger stderr vers stdout pour capturer toutes les sorties
            pb.redirectErrorStream(true);

            Process process = pb.start();
            LocalMcpConnection connection = new LocalMcpConnection(serverId, process, objectMapper);

            // Tenter le handshake initial
            if (performLocalHandshake(connection)) {
                connection.setConnected(true);
                log.info("Connexion MCP locale etablie: {}", serverId);
                return connection;
            } else {
                connection.close();
                throw new McpException("echec du handshake MCP local");
            }

        } catch (Exception e) {
            throw new McpException("Erreur lors de la creation de la connexion locale: " + e.getMessage(), e);
        }
    }

    /**
     * Creer une connexion MCP distante via HTTP
     */
    public RemoteMcpConnection createRemoteConnection(String serverId, String baseUrl, 
                                                    Map<String, String> headers) throws McpException {
        try {
            log.info("Creation connexion MCP distante: {} vers {}", serverId, baseUrl);

            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            
            if (headers != null) {
                headers.forEach(httpHeaders::add);
            }

            RemoteMcpConnection connection = new RemoteMcpConnection(serverId, baseUrl, httpHeaders, restTemplate, objectMapper);

            // Tenter le handshake initial
            if (performRemoteHandshake(connection)) {
                connection.setConnected(true);
                log.info("Connexion MCP distante etablie: {}", serverId);
                return connection;
            } else {
                throw new McpException("echec du handshake MCP distant");
            }

        } catch (Exception e) {
            throw new McpException("Erreur lors de la creation de la connexion distante: " + e.getMessage(), e);
        }
    }

    /**
     * Creer une connexion API Gateway
     */
    public ApiGatewayConnection createApiGatewayConnection(String serverId, String baseUrl, 
                                                         Map<String, String> headers) throws McpException {
        try {
            log.info("Creation connexion API Gateway: {} vers {}", serverId, baseUrl);

            HttpHeaders httpHeaders = new HttpHeaders();
            if (headers != null) {
                headers.forEach(httpHeaders::add);
            }

            ApiGatewayConnection connection = new ApiGatewayConnection(serverId, baseUrl, httpHeaders, restTemplate);

            // Test de connectivite simple
            if (testApiGatewayConnectivity(connection)) {
                connection.setConnected(true);
                log.info("Connexion API Gateway etablie: {}", serverId);
                return connection;
            } else {
                throw new McpException("API Gateway inaccessible");
            }

        } catch (Exception e) {
            throw new McpException("Erreur lors de la creation de la connexion API Gateway: " + e.getMessage(), e);
        }
    }

    /**
     * Effectuer le handshake MCP local
     */
    private boolean performLocalHandshake(LocalMcpConnection connection) {
        try {
            McpRequest initRequest = McpRequest.initialize(requestIdCounter.getAndIncrement());
            McpResponse response = connection.sendRequest(initRequest);
            
            return response != null && !response.isError();
        } catch (Exception e) {
            log.warn("echec du handshake MCP local: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Effectuer le handshake MCP distant
     */
    private boolean performRemoteHandshake(RemoteMcpConnection connection) {
        try {
            McpRequest initRequest = McpRequest.initialize(requestIdCounter.getAndIncrement());
            McpResponse response = connection.sendRequest(initRequest);
            
            return response != null && !response.isError();
        } catch (Exception e) {
            log.warn("echec du handshake MCP distant: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Tester la connectivite API Gateway
     */
    private boolean testApiGatewayConnectivity(ApiGatewayConnection connection) {
        try {
            // Test simple de connectivite
            ResponseEntity<String> response = connection.getRestTemplate().exchange(
                connection.getFullUrl("/"),
                HttpMethod.GET,
                new HttpEntity<>(connection.getHeaders()),
                String.class
            );
            
            // Accepter les codes 2xx et 4xx (4xx peut etre normal si l'endpoint necessite des parametres)
            return response.getStatusCode().is2xxSuccessful() || response.getStatusCode().is4xxClientError();
        } catch (Exception e) {
            log.warn("Test de connectivite API Gateway echoue: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Lister les outils disponibles sur une connexion MCP
     */
    public List<McpTool> listTools(McpConnection connection) throws McpException {
        if (connection.getServerType().equals("API_GATEWAY")) {
            throw new McpException("Les API Gateway necessitent une configuration manuelle des outils");
        }

        try {
            McpRequest listToolsRequest = McpRequest.listTools(requestIdCounter.getAndIncrement());
            McpResponse response = connection.sendRequest(listToolsRequest);

            if (response.isError()) {
                throw new McpException("Erreur lors de la liste des outils: " + response.getError());
            }

            return parseToolsFromResponse(response);

        } catch (Exception e) {
            throw new McpException("Erreur lors de la recuperation des outils: " + e.getMessage(), e);
        }
    }

    /**
     * Appeler un outil MCP
     */
    public McpToolResult callTool(McpConnection connection, String toolName, JsonNode arguments) throws McpException {
        try {
            if (connection.getServerType().equals("API_GATEWAY")) {
                throw new McpException("Utilisez callApiGatewayTool pour les connexions API Gateway");
            }

            McpRequest toolRequest = McpRequest.callTool(requestIdCounter.getAndIncrement(), toolName, arguments);
            McpResponse response = connection.sendRequest(toolRequest);

            if (response.isError()) {
                return McpToolResult.error(response.getError().getMessage());
            }

            return McpToolResult.success(response.getResult());

        } catch (Exception e) {
            throw new McpException("Erreur lors de l'appel d'outil: " + e.getMessage(), e);
        }
    }

    /**
     * Appeler un outil via API Gateway
     */
    public McpToolResult callApiGatewayTool(ApiGatewayConnection connection, String endpoint, 
                                          String method, JsonNode arguments, 
                                          Map<String, String> additionalHeaders) throws McpException {
        try {
            HttpHeaders headers = new HttpHeaders(connection.getHeaders());
            if (additionalHeaders != null) {
                additionalHeaders.forEach(headers::add);
            }

            String fullUrl = connection.getFullUrl(endpoint);
            HttpMethod httpMethod = HttpMethod.valueOf(method.toUpperCase());
            
            HttpEntity<JsonNode> request = new HttpEntity<>(arguments, headers);
            
            ResponseEntity<Object> response = connection.getRestTemplate().exchange(
                fullUrl, httpMethod, request, Object.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                return McpToolResult.success(response.getBody());
            } else {
                return McpToolResult.error("Erreur HTTP: " + response.getStatusCode());
            }

        } catch (Exception e) {
            throw new McpException("Erreur lors de l'appel API Gateway: " + e.getMessage(), e);
        }
    }

    /**
     * Parser les outils depuis une reponse MCP
     */
    private List<McpTool> parseToolsFromResponse(McpResponse response) {
        List<McpTool> tools = new ArrayList<>();
        
        try {
            JsonNode result = response.getResult();
            if (result != null && result.has("tools")) {
                JsonNode toolsNode = result.get("tools");
                if (toolsNode.isArray()) {
                    for (JsonNode toolNode : toolsNode) {
                        McpTool tool = objectMapper.treeToValue(toolNode, McpTool.class);
                        tools.add(tool);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Erreur lors du parsing des outils: {}", e.getMessage());
        }
        
        return tools;
    }

    /**
     * Generer un ID de requete unique
     */
    public long getNextRequestId() {
        return requestIdCounter.getAndIncrement();
    }
}
