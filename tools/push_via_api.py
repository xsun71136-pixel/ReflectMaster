#!/usr/bin/env python3
"""Push a local commit to GitHub through the Git Data API.

`git push` needs github.com:443, which is unreachable from this sandbox, while
api.github.com works. So the commit is rebuilt server side:

  1. read the desired tree from the local commit (`git ls-tree -r`)
  2. upload only the blobs the server does not already have
  3. create a tree (flat recursive list, no base_tree => deletions are implicit)
  4. create the commit with the original message and parent
  5. move refs/heads/master to it
"""
import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

TOKEN = os.environ.get("GH_TOKEN") or open(os.path.expanduser("~/.github_token")).read().strip()
OWNER = "xsun71136-pixel"
REPO = "ReflectMaster"
API = "https://api.github.com/repos/%s/%s" % (OWNER, REPO)
BRANCH = "master"


def sh(args, cwd=None):
    return subprocess.run(args, cwd=cwd, capture_output=True, check=True).stdout


def api(path, method="GET", data=None, raw=False):
    url = path if path.startswith("http") else API + path
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=body, method=method, headers={
        "Authorization": "Bearer " + TOKEN,
        "Accept": "application/vnd.github+json",
        "User-Agent": "rm-push",
        **({"Content-Type": "application/json"} if body else {}),
    })
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                payload = resp.read()
                return payload if raw else (json.loads(payload) if payload else {})
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:400]
            if e.code in (403, 429) or e.code >= 500:
                sys.stderr.write("retry %d on HTTP %d: %s\n" % (attempt, e.code, detail))
                continue
            raise SystemExit("HTTP %d %s %s -> %s" % (e.code, method, url, detail))
        except Exception as e:
            sys.stderr.write("retry %d on %r\n" % (attempt, e))
    raise SystemExit("gave up on %s %s" % (method, url))


def main():
    local_commit = sys.argv[1] if len(sys.argv) > 1 else "HEAD"
    local_commit = sh(["git", "rev-parse", local_commit]).decode().strip()
    message = sh(["git", "log", "-1", "--pretty=%B", local_commit]).decode()

    # Parent resolution: prefer the local parent when the server already has it,
    # otherwise stack on top of the remote branch head. `git push` is not usable
    # here (github.com:443 is unreachable), so local and remote commit identities
    # can legitimately diverge while their trees agree.
    remote_head = api("/git/refs/heads/" + BRANCH)["object"]["sha"]
    local_parent = sh(["git", "rev-parse", local_commit + "^"]).decode().strip()
    parent = local_parent
    try:
        api("/git/commits/" + local_parent)
    except SystemExit:
        parent = remote_head
        print("local parent %s unknown to server; using remote head %s"
              % (local_parent[:8], parent[:8]))
    if parent == local_commit:
        raise SystemExit("nothing to push")

    # Tree the server already has for the parent commit.
    remote_parent = api("/git/commits/" + parent)
    existing = set()
    listing = api("/git/trees/" + remote_parent["tree"]["sha"] + "?recursive=1")
    for entry in listing.get("tree", []):
        if entry["type"] == "blob":
            existing.add(entry["sha"])
    if listing.get("truncated"):
        raise SystemExit("parent tree listing was truncated; refusing to guess")
    print("server already has %d blobs" % len(existing))

    # Desired tree.
    entries = []
    out = sh(["git", "ls-tree", "-r", "-z", local_commit]).decode("utf-8", "surrogateescape")
    for record in out.split("\0"):
        if not record.strip():
            continue
        meta, path = record.split("\t", 1)
        mode, kind, sha = meta.split()
        if kind != "blob":
            raise SystemExit("unexpected object type %s at %s" % (kind, path))
        entries.append({"path": path, "mode": mode, "type": "blob", "sha": sha})
    print("desired tree: %d files" % len(entries))

    missing = sorted({e["sha"] for e in entries} - existing)
    print("uploading %d new blobs" % len(missing))
    for i, sha in enumerate(missing, 1):
        blob = sh(["git", "cat-file", "blob", sha])
        api("/git/blobs", "POST", {
            "content": base64.b64encode(blob).decode(),
            "encoding": "base64",
        })
        if i % 10 == 0 or i == len(missing):
            print("  %d/%d" % (i, len(missing)))

    tree = api("/git/trees", "POST", {"tree": entries})
    print("tree   %s" % tree["sha"])

    commit = api("/git/commits", "POST", {
        "message": message,
        "tree": tree["sha"],
        "parents": [parent],
    })
    print("commit %s" % commit["sha"])

    ref = api("/git/refs/heads/" + BRANCH, "PATCH", {"sha": commit["sha"], "force": True})
    print("ref    %s -> %s" % (ref["ref"], ref["object"]["sha"]))


if __name__ == "__main__":
    main()
