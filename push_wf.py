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

tag = api('GET', 'https://api.github.com/repos/Simiely/android-adskip/git/refs/tags/v1.0')
tag_obj = api('GET', tag['object']['url'])
if 'tree' not in tag_obj:
    tag_obj = api('GET', tag_obj['object']['url'])
tree_sha = tag_obj['tree']['sha']
tree = api('GET', f'https://api.github.com/repos/Simiely/android-adskip/git/trees/{tree_sha}?recursive=1')

# Added SettingsActivity to the restore list
targets = [
    'app/src/main/java/com/simiely/adskip/ui/SettingsActivity.kt',
]

ref = api('GET', 'https://api.github.com/repos/Simiely/android-adskip/git/refs/heads/main')
current_tree = api('GET', ref['object']['url'])['tree']['sha']

tree_items = []
for item in tree['tree']:
    if item['path'] in targets:
        content = raw(item['url'])
        blob = api('POST', 'https://api.github.com/repos/Simiely/android-adskip/git/blobs', {'content': content, 'encoding': 'utf-8'})
        tree_items.append({'path': item['path'], 'mode': item['mode'], 'type': 'blob', 'sha': blob['sha']})
        print(f'Restored: {item["path"]}')

new_tree = api('POST', 'https://api.github.com/repos/Simiely/android-adskip/git/trees', {'base_tree': current_tree, 'tree': tree_items})
cm = api('POST', 'https://api.github.com/repos/Simiely/android-adskip/git/commits', {'message': 'fix: also revert SettingsActivity to v1.0', 'tree': new_tree['sha'], 'parents': [ref['object']['sha']]})
api('PATCH', 'https://api.github.com/repos/Simiely/android-adskip/git/refs/heads/main', {'sha': cm['sha']})
print(f'DONE: {cm["sha"][:8]}')
