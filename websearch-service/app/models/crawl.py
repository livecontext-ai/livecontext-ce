from pydantic import BaseModel, Field


class CrawlOptions(BaseModel):
    screenshots: bool = True
    timeout_ms: int = 30000
    callback_url: str | None = None


class CrawlRequest(BaseModel):
    url: str
    options: CrawlOptions = Field(default_factory=CrawlOptions)


class Screenshot(BaseModel):
    base64: str
    timestamp_ms: int


class CrawlPageResult(BaseModel):
    url: str
    markdown: str
    metadata: dict = Field(default_factory=dict)
    screenshots: list[Screenshot] = Field(default_factory=list)
    screenshot_key: str | None = None
    crawl_time_ms: int = 0


class CrawlResponse(BaseModel):
    url: str
    markdown: str
    metadata: dict = Field(default_factory=dict)
    screenshots: list[Screenshot] = Field(default_factory=list)
    screenshot_key: str | None = None
    crawl_time_ms: int = 0


class FetchBatchResponse(BaseModel):
    pages: list[CrawlPageResult] = Field(default_factory=list)
    total_time_ms: int = 0
