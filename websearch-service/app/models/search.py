from pydantic import BaseModel, Field


class SearchRequest(BaseModel):
    query: str
    max_results: int = Field(default=8, ge=1, le=50)
    categories: list[str] = Field(default_factory=lambda: ["general"])
    language: str = "auto"
    time_range: str | None = None


class SearchResult(BaseModel):
    url: str
    title: str
    snippet: str
    source: str | None = None
    score: float | None = None
    published_date: str | None = None


class SearchResponse(BaseModel):
    query: str
    results: list[SearchResult]
    total_results: int
    search_time_ms: int
