from pydantic import BaseModel, Field


class JobSubmitRequest(BaseModel):
    action: str  # "fetch", "search"
    parameters: dict = Field(default_factory=dict)


class JobSubmitResponse(BaseModel):
    job_id: str
    status: str = "accepted"
