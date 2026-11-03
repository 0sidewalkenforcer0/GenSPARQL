#!/usr/bin/env python3
"""An OpenAI-compatible stub that answers every chat request with an empty array.

The number of LLM calls of a query does not depend on the answers, so the counts of
measure_calls.py come from this stub instead of a model. It serves GET /v1/models and
POST /v1/chat/completions.

  stub_llm.py PORT
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def _send(self, body):
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self._send({"object": "list", "data": [{"id": "stub", "object": "model"}]})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        self.rfile.read(length)
        self._send({"id": "stub", "object": "chat.completion", "model": "stub",
                    "choices": [{"index": 0, "finish_reason": "stop",
                                 "message": {"role": "assistant", "content": "[]"}}],
                    "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}})

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
