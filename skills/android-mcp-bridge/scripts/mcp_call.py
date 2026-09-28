#!/usr/bin/env python3
"""Android MCP Bridge JSON-RPC 客户端（纯标准库）。

用法：
  python3 mcp_call.py list
  python3 mcp_call.py call device.battery
  python3 mcp_call.py call device.settings.brightness.set '{"value":128,"confirm":true}'
  python3 mcp_call.py --host ::1 list
"""
import argparse
import json
import sys
import urllib.request

PORT = 18765
PATH = "/mcp"
DEFAULT_TIMEOUT = 130  # 预留截屏/录屏的系统授权等待


def base_url(host: str) -> str:
    if ":" in host:
        return f"http://[{host}]:{PORT}{PATH}"
    return f"http://{host}:{PORT}{PATH}"


def post(url: str, payload: dict) -> dict:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=data, method="POST", headers={
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "MCP-Protocol-Version": "2025-03-26",
    })
    with urllib.request.urlopen(request, timeout=DEFAULT_TIMEOUT) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser(description="Android MCP Bridge JSON-RPC 客户端")
    parser.add_argument("--host", default="127.0.0.1", help="默认 127.0.0.1，可传 ::1")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list", help="列出全部工具")
    call_parser = sub.add_parser("call", help="调用一个工具")
    call_parser.add_argument("name", help="工具名，如 device.battery")
    call_parser.add_argument("args", nargs="?", default="{}", help='JSON 参数，如 \'{"value":128,"confirm":true}\'')
    args = parser.parse_args()

    url = base_url(args.host)
    if args.cmd == "list":
        reply = post(url, {"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}})
        for tool in reply.get("result", {}).get("tools", []):
            print(f"{tool['name']}\t{tool.get('description', '')}")
        return

    try:
        arguments = json.loads(args.args)
    except json.JSONDecodeError as error:
        sys.exit(f"参数不是合法 JSON：{error}")
    reply = post(url, {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                       "params": {"name": args.name, "arguments": arguments}})
    result = reply.get("result", {})
    if result.get("isError"):
        sys.exit("工具返回错误：" + "".join(c.get("text", "") for c in result.get("content", [])))
    for chunk in result.get("content", []):
        if chunk.get("type") == "text":
            print(chunk.get("text", ""))


if __name__ == "__main__":
    main()
