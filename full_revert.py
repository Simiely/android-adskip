import urllib.request, json, ssl

token = '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'
ctx = ssl.create_default_context()

def api(method, url, data=None):
    req = urllib.request.Request(url, method=method)
    req.add_header('Authorization', f'Bearer {token}')
    req.add_header('Accept', 'application/vnd.github+json')
    if data: req.add_header('Content-Type', 'application/json'); req.data = json.dumps(data).encode()
    with urllib.request.urlopen(req, context=ctx) as r: return json.loads(r.read())

def raw(url):
    req = urllib.request.Request(url)
    req.add_header('Authorization', f'Bearer {token}')
    req.add_header('Accept', 'application/vnd.github.v3.raw')
    with urllib.request.urlopen(req, context=ctx) as r: return r.read().decode()

# Get v1.0 and current trees
tag = api('GET', 'https://api.github.com/repos/Simiely/android-adskip/git/refs/tags/v1.0')
tag_obj = api('GET', tag['object']['url'])
if 'tree' not in tag_obj: tag_obj = api('GET', tag_obj['object']['url'])
v10 = api('GET', f'https://api.github.com/repos/Simiely/android-adskip/git/trees/{tag_obj["tree"]["sha"]}?recursive=1')

ref = api('GET', 'https://api.github.com/repos/Simiely/android-adskip/git/refs/heads/main')
main = api('GET', f'https://api.github.com/repos/Simiely/android-adskip/git/trees/{api("GET", ref["object"]["url"])["tree"]["sha"]}?recursive=1')

v10_map = {item['path']: item for item in v10['tree']}
main_map = {item['path']: item for item in main['tree']}

# Revert all modified source files to v1.0
tree_items = []
restored = []
for path, v10_item in v10_map.items():
    if path.endswith('.kt') or path.endswith('.xml') or path.endswith('.kts') or path.endswith('.pro'):
        if path not in main_map or main_map[path]['sha'] != v10_item['sha']:
            content = raw(v10_item['url'])
            blob = api('POST', 'https://api.github.com/repos/Simiely/android-adskip/git/blobs', {'content': content, 'encoding': 'utf-8'})
            tree_items.append({'path': path, 'mode': v10_item['mode'], 'type': 'blob', 'sha': blob['sha']})
            restored.append(path)
            print(f'Reverted: {path}')

# Delete new files not in v1.0
deleted = []
for path in main_map:
    if path not in v10_map and (path.endswith('.kt') or path.endswith('.xml')):
        tree_items.append({'path': path, 'mode': main_map[path]['mode'], 'type': 'blob', 'sha': None})
        deleted.append(path)
        print(f'Deleted: {path}')

new_tree = api('POST', 'https://api.github.com/repos/Simiely/android-adskip/git/trees', {
    'base_tree': api('GET', ref['object']['url'])['tree']['sha'],
    'tree': tree_items
})
cm = api('POST', 'https://api.github.com/repos/Simiely/android-adskip/git/commits', {
    'message': f'revert: full restore to v1.0 ({len(restored)} reverted, {len(deleted)} deleted)',
    'tree': new_tree['sha'],
    'parents': [ref['object']['sha']]
})
api('PATCH', 'https://api.github.com/repos/Simiely/android-adskip/git/refs/heads/main', {'sha': cm['sha']})
print(f'\nDONE: {cm["sha"][:8]} - {len(restored)} reverted, {len(deleted)} deleted')
