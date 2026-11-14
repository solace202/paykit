#!/usr/bin/env python3
"""Captures the README screenshots from a running local instance in demo mode (docker compose up).

Requires: pip install playwright && playwright install chromium   (or pass --chrome to use an installed Chrome)
Usage:    python scripts/capture_screenshots.py [--base-url http://localhost:8080] [--chrome] [--only pricing,account]
Shots:    pricing, checkout, account, proration, webhook-log, swagger, tests (needs `mvn verify` first)
"""
import argparse
import base64
import html
import json
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "screenshots"
ADMIN = ("admin", "change-me")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.auth = "Basic " + base64.b64encode(f"{ADMIN[0]}:{ADMIN[1]}".encode()).decode()
        self.form_opener = urllib.request.build_opener(NoRedirect)

    def call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        req.add_header("Authorization", self.auth)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                raw = r.read()
                return r.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            raw = e.read()
            return e.code, json.loads(raw) if raw else None

    def form(self, path):
        """POST a demo button (the pages answer with a redirect)."""
        req = urllib.request.Request(self.base + path, data=b"", method="POST")
        try:
            self.form_opener.open(req, timeout=60)
        except urllib.error.HTTPError as e:
            if e.code not in (302, 303):
                raise


# ---------------------------------------------------------------- terminal rendering
REASONS = {200: "OK", 201: "Created", 202: "Accepted", 400: "Bad Request", 404: "Not Found", 409: "Conflict"}


def colorize_json(obj):
    text = json.dumps(obj, indent=2, ensure_ascii=False)
    out = []
    for line in text.splitlines():
        esc = html.escape(line)
        if '": ' in line:
            key, _, val = esc.partition("&quot;: ")
            key = f'<span class="k">{key}&quot;</span>: '
            out.append(key + value_span(val))
        else:
            out.append(value_span(esc))
    return "\n".join(out)


def value_span(v):
    stripped = v.rstrip(",")
    tail = v[len(stripped):]
    if stripped.startswith("&quot;"):
        return f'<span class="s">{stripped}</span>{tail}'
    if stripped in ("true", "false", "null"):
        return f'<span class="b">{stripped}</span>{tail}'
    if stripped[:1].isdigit() or stripped[:1] == "-":
        return f'<span class="n">{stripped}</span>{tail}'
    return v


def terminal_page(title, blocks):
    body = []
    for b in blocks:
        if b[0] == "comment":
            body.append(f'<div class="c"># {html.escape(b[1])}</div>')
        elif b[0] == "cmd":
            body.append(f'<div class="cmd"><span class="p">$</span> {html.escape(b[1])}</div>')
        elif b[0] == "resp":
            status, payload = b[1], b[2]
            cls = "ok" if status < 400 else "err"
            body.append(f'<div class="st {cls}">HTTP/1.1 {status} {REASONS.get(status, "")}</div>')
            if payload is not None:
                body.append(f"<pre>{colorize_json(payload)}</pre>")
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>
body{{margin:0;background:#e9edf2;font-family:-apple-system,Segoe UI,Roboto,sans-serif;padding:26px}}
.win{{background:#0d1117;border-radius:10px;box-shadow:0 8px 30px rgba(0,0,0,.25);overflow:hidden;max-width:1180px}}
.bar{{background:#161b22;padding:10px 14px;display:flex;align-items:center;gap:8px;border-bottom:1px solid #30363d}}
.dot{{width:12px;height:12px;border-radius:50%}} .t{{color:#8b949e;font-size:13px;margin-left:12px}}
.term{{padding:16px 20px 20px;font:13.5px/1.5 "JetBrains Mono","DejaVu Sans Mono",Menlo,Consolas,monospace;color:#c9d1d9}}
.c{{color:#7ee787;margin-top:14px}} .c:first-child{{margin-top:0}} .cmd{{color:#e6edf3;white-space:pre-wrap;word-break:break-all}}
.p{{color:#58a6ff;font-weight:bold}} .st{{font-weight:bold;margin-top:4px}} .ok{{color:#3fb950}} .err{{color:#ff7b72}}
pre{{margin:2px 0 0;font:inherit;white-space:pre-wrap;word-break:break-word}}
.k{{color:#79c0ff}} .s{{color:#a5d6ff}} .n{{color:#ffa657}} .b{{color:#ff7b72}}
</style></head><body><div class="win"><div class="bar">
<span class="dot" style="background:#ff5f56"></span><span class="dot" style="background:#ffbd2e"></span>
<span class="dot" style="background:#27c93f"></span><span class="t">{html.escape(title)}</span></div>
<div class="term">{''.join(body)}</div></div></body></html>"""



# ---------------------------------------------------------------- demo data
def subscribe(api, email, plan, interval):
    s, acc = api.call("POST", "/api/accounts", {"email": email, "name": email.split("@")[0].title()})
    if s == 409:
        raise SystemExit(f"{email} already exists: run against a fresh database (docker compose down -v)")
    _, co = api.call("POST", f"/api/accounts/{acc['id']}/checkout", {"plan": plan, "interval": interval})
    return acc, co


def seed(api):
    data = {}
    # Maria: Pro trial -> trial ends -> upgrade to Team -> renewal declined twice -> Stripe redelivers an event
    maria, co = subscribe(api, "maria@northwind.io", "pro", "month")
    api.form(f"/demo/checkout/{co['checkoutId']}/pay")
    api.form(f"/demo/accounts/{maria['id']}/end-trial")
    data["preview"] = api.call("POST", f"/api/accounts/{maria['id']}/subscription/preview", {"plan": "team", "interval": "month"})
    data["change"] = api.call("POST", f"/api/accounts/{maria['id']}/subscription/change", {"plan": "team", "interval": "month"})
    data["after_change"] = api.call("GET", f"/api/accounts/{maria['id']}/entitlements")
    data["downgrade"] = api.call("POST", f"/api/accounts/{maria['id']}/subscription/preview", {"plan": "starter", "interval": "month"})
    time.sleep(1)
    api.form(f"/demo/accounts/{maria['id']}/renewal-failed")
    time.sleep(1)
    api.form(f"/demo/accounts/{maria['id']}/renewal-failed")
    api.form(f"/demo/accounts/{maria['id']}/redeliver")
    data["maria"] = maria
    # Li Wei: Starter yearly trial, converts, then cancels at period end
    li, co = subscribe(api, "li.wei@acme.dev", "starter", "year")
    api.form(f"/demo/checkout/{co['checkoutId']}/pay")
    api.form(f"/demo/accounts/{li['id']}/end-trial")
    api.call("POST", f"/api/accounts/{li['id']}/subscription/cancel")
    # Sam: Team (no trial), renewal fails once, retry succeeds
    sam, co = subscribe(api, "sam@globex.com", "team", "month")
    api.form(f"/demo/checkout/{co['checkoutId']}/pay")
    api.form(f"/demo/accounts/{sam['id']}/renewal-failed")
    api.form(f"/demo/accounts/{sam['id']}/retry-succeeded")
    # Jordan: still on the checkout page
    _, data["pending_checkout"] = subscribe(api, "jordan@initech.com", "pro", "year")
    time.sleep(6)  # let the outbox relay deliver the notifications
    return data


def shot_html(page, html_text, path, width=1240):
    with tempfile.NamedTemporaryFile("w", suffix=".html", delete=False, encoding="utf-8") as f:
        f.write(html_text)
    page.set_viewport_size({"width": width, "height": 200})
    page.goto(Path(f.name).as_uri())
    page.wait_for_timeout(200)
    page.screenshot(path=str(path), full_page=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--chrome", action="store_true", help="use the installed Google Chrome")
    parser.add_argument("--only", default="", help="comma-separated subset of the shots")
    args = parser.parse_args()
    only = set(filter(None, args.only.split(",")))
    want = lambda n: not only or n in only  # noqa: E731
    OUT.mkdir(parents=True, exist_ok=True)
    api = Api(args.base_url)
    host = args.base_url.rstrip("/")
    needs_data = any(want(n) for n in ("checkout", "account", "proration", "webhook-log"))
    data = seed(api) if needs_data else None

    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome" if args.chrome else None)
        ctx = browser.new_context(viewport={"width": 1280, "height": 820}, device_scale_factor=1,
                                  http_credentials={"username": ADMIN[0], "password": ADMIN[1]})
        page = ctx.new_page()

        if want("pricing"):
            page.goto(f"{host}/pricing")
            page.wait_for_timeout(300)
            page.screenshot(path=str(OUT / "pricing.png"), full_page=True)

        if want("checkout"):
            page.goto(data["pending_checkout"]["url"])
            page.set_viewport_size({"width": 1280, "height": 720})
            page.wait_for_timeout(300)
            page.screenshot(path=str(OUT / "checkout.png"))
            page.set_viewport_size({"width": 1280, "height": 820})

        if want("account"):
            page.goto(f"{host}/account/{data['maria']['id']}")
            page.wait_for_timeout(300)
            page.screenshot(path=str(OUT / "account.png"), full_page=True)

        if want("proration"):
            mid = data["maria"]["id"]
            (s1, r1), (s2, r2), (s3, r3), (s4, r4) = data["preview"], data["change"], data["after_change"], data["downgrade"]
            blocks = [
                ("comment", "Preview an upgrade Pro -> Team: Stripe prorates the rest of the period and invoices it now"),
                ("cmd", f"curl -s -u admin:*** -X POST {host}/api/accounts/{mid[:8]}…/subscription/preview -d '{{\"plan\":\"team\",\"interval\":\"month\"}}'"),
                ("resp", s1, r1),
                ("comment", "Apply it: sent to Stripe with an Idempotency-Key; the local plan changes when the webhook confirms"),
                ("cmd", f"curl -s -u admin:*** -X POST {host}/api/accounts/{mid[:8]}…/subscription/change -d '{{\"plan\":\"team\",\"interval\":\"month\"}}' | jq '{{direction, state}}'"),
                ("resp", s2, {k: r2[k] for k in ("direction", "state")}),
                ("comment", "customer.subscription.updated arrived -> entitlements recomputed"),
                ("cmd", f"curl -s -u admin:*** {host}/api/accounts/{mid[:8]}…/entitlements"),
                ("resp", s3, r3),
                ("comment", "A downgrade would credit the unused time on the next invoice instead"),
                ("cmd", f"curl -s -u admin:*** -X POST {host}/api/accounts/{mid[:8]}…/subscription/preview -d '{{\"plan\":\"starter\",\"interval\":\"month\"}}' | jq '{{direction, prorationBehavior, preview}}'"),
                ("resp", s4, {k: r4[k] for k in ("direction", "prorationBehavior", "preview")}),
            ]
            shot_html(page, terminal_page("plan change with proration — bash", blocks), OUT / "proration.png")

        if want("webhook-log"):
            page.goto(f"{host}/admin/events")
            page.wait_for_timeout(300)
            page.screenshot(path=str(OUT / "webhook-log.png"), full_page=True)

        if want("swagger"):
            page.goto(f"{host}/swagger-ui/index.html")
            page.wait_for_selector(".opblock", timeout=30_000)
            page.set_viewport_size({"width": 1280, "height": 1250})
            page.wait_for_timeout(600)
            page.screenshot(path=str(OUT / "swagger.png"), full_page=False)
            page.set_viewport_size({"width": 1280, "height": 820})

        if want("tests"):
            report = Path(tempfile.gettempdir()) / "paykit-test-report.html"
            rc = subprocess.run([sys.executable, str(ROOT / "scripts" / "test_report.py"), "--html", str(report)])
            if report.exists():
                page.set_viewport_size({"width": 1100, "height": 700})
                page.goto(report.as_uri())
                page.screenshot(path=str(OUT / "tests.png"), full_page=True)
            else:
                print(f"PENDING: tests (no surefire reports, exit {rc.returncode}; run mvn verify first)")

        browser.close()
    print("Saved to", OUT)


if __name__ == "__main__":
    main()
