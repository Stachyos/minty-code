"""Shared context policy for the personal agent and benchmark runs."""
import json


def estimate_tokens(messages: list) -> int:
    return len(json.dumps(messages, default=str)) // 4


def microcompact(messages: list):
    results = [part for message in messages
               if message['role'] == 'user' and isinstance(message.get('content'), list)
               for part in message['content']
               if isinstance(part, dict) and part.get('type') == 'tool_result']
    for part in results[:-3]:
        if isinstance(part.get('content'), str) and len(part['content']) > 100:
            part['content'] = '[cleared]'
