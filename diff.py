import urllib.request, json, ssl

token = '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'
ctx = ssl.create_default_context()

def api(method, url, data=None):
    req = urllib.request.Request(url, method=method)
    req.add_header('Authorization', f'Bearer {token}')
    req.add_header('Accept', 'application/vnd.github+json')
    if data: req.add_header('Content-Type', 'application/json'); req.data = json.dumps(data).encode()
    with urllib.request.urlopen(req, context=ctx) as r: return json.loads(r.read())

# Get v1.0 tree
tag = api('GET', 'https://api.github.com/repos/Simiely/android-adskip/git/refs/tags/v1.0')
tag_obj = api('GET', tag['object']['url'])
if 'tree' not in tag_obj:
    tag_obj = api('GET', tag_obj['object']['url'])
v10_tree = api('GET', f'https://api.github.com/repos/Simiely/android-adskip/git/trees/{tag_obj["tree"]["sha"]}?recursive=1')

# Get current tree
ref = api('GET', 'https://api.github.com/repos/Simiely/android-adskip/git/refs/heads/main')
current_obj = api('GET', ref['object']['url'])
current_tree = api('GET', f'https://api.github.com/repos/Simiely/android-adskip/git/trees/{current_obj["tree"]["sha"]}?recursive=1')

# Build maps
v10_map = {item['path']: item['sha'] for item in v10_tree['tree']}
current_map = {item['path']: item['sha'] for item in current_tree['tree']}

# Find differences (only source files)
changed = []
for path, sha in v10_map.items():
    if path.endswith('.kt') or path.endswith('.xml') or path.endswith('.kts') or path.endswith('.pro'):
        if path not in current_map or current_map[path] != sha:
            changed.append(path)

# New files not in v1.0
new_files = [p for p in current_map if p not in v10_map and (p.endswith('.kt') or p.endswith('.xml'))]

print("=== Changed since v1.0 ===")
for p in sorted(changed):
    print(f"  MODIFIED: {p}")
print(f"\n=== New since v1.0 ===")
for p in sorted(new_files):
    print(f"  NEW: {p}")
print(f"\nTotal changes: {len(changed)} modified + {len(new_files)} new")
