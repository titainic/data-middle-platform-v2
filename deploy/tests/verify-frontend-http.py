#!/usr/bin/env python3
import argparse
import concurrent.futures
import json
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path


def fetch(url, expected):
    request = urllib.request.Request(url, headers={"Accept": expected, "Accept-Encoding": "identity"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            content_type = response.headers.get("Content-Type", "").split(";", 1)[0].lower()
            body = response.read()
            if response.status != 200:
                return {"url": url, "status": response.status, "contentType": content_type}
            if expected == "text/html":
                valid = content_type == "text/html" and b'id="app"' in body and b"/assets/" in body
            else:
                allowed = {"text/javascript", "application/javascript"} if expected == "text/javascript" else {"text/css"}
                valid = content_type in allowed and bool(body) and not body.lstrip().startswith(b"<")
            if not valid:
                return {"url": url, "status": response.status, "contentType": content_type,
                        "error": "Unexpected resource content"}
    except (OSError, urllib.error.URLError) as error:
        return {"url": url, "error": str(error)}
    return None


def main():
    parser = argparse.ArgumentParser(description="Verify real qData login HTML and every packaged JS/CSS over HTTP")
    parser.add_argument("base_url")
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=8)
    arguments = parser.parse_args()
    base = arguments.base_url.rstrip("/")
    errors = []
    for route in ("/", "/login", "/login/"):
        result = fetch(base + route, "text/html")
        if result:
            errors.append(result)
    prefix = "BOOT-INF/classes/dist/admin/assets/"
    with zipfile.ZipFile(arguments.jar) as archive:
        resources = [name[len(prefix):] for name in archive.namelist()
                     if name.startswith(prefix) and name.endswith((".js", ".css"))]
    if not resources:
        raise ValueError("The application JAR has no actual dist/admin JS/CSS resources")
    def check(resource):
        return fetch(base + "/assets/" + urllib.parse.quote(resource, safe="/"),
                     "text/javascript" if resource.endswith(".js") else "text/css")
    with concurrent.futures.ThreadPoolExecutor(max_workers=arguments.workers) as executor:
        for error in executor.map(check, resources):
            if error:
                errors.append(error)
    report = {"status": "PASS" if not errors else "FAIL", "htmlRoutes": 3,
              "jsCssResources": len(resources), "errors": errors}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if errors:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
