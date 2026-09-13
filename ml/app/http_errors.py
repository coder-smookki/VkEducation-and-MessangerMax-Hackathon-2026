"""Bound the actual request stream, including chunked bodies, before JSON parsing."""

from typing import Literal

from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from app.contracts import Contract

MAX_REQUEST_BYTES = 512 * 1024


class CompareInputError(Contract):
    detail: Literal["INVALID_COMPARE_REQUEST"]


class IntakeInputError(Contract):
    detail: Literal["INVALID_INTAKE_REQUEST"]


class BodySizeError(Contract):
    detail: Literal["REQUEST_TOO_LARGE"]


class RequestSizeLimit:
    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send):
        if scope["type"] != "http" or scope["method"] != "POST":
            return await self.app(scope, receive, send)
        chunks = []
        size = 0
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            body = message.get("body", b"")
            size += len(body)
            if size > MAX_REQUEST_BYTES:
                response = JSONResponse(status_code=413, content={"detail": "REQUEST_TOO_LARGE"})
                return await response(scope, receive, send)
            chunks.append(body)
            if not message.get("more_body", False):
                break
        buffered = b"".join(chunks)
        delivered = False

        async def limited_receive():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": buffered, "more_body": False}
            return await receive()

        await self.app(scope, limited_receive, send)
