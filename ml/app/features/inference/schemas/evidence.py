from pydantic import Field, model_validator

from app.shared.schemas import Contract


class Evidence(Contract):
    # Unicode code points. Python str offsets match this; Java/JS must convert from UTF-16.
    start: int = Field(strict=True, ge=0)
    end: int = Field(strict=True, ge=1)
    quote: str = Field(min_length=1, max_length=5000)

    @model_validator(mode="after")
    def ordered_span(self):
        if self.end <= self.start or self.end - self.start != len(self.quote):
            raise ValueError("Invalid evidence span")
        return self
