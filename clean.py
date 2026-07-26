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

# Get v1.0 tree
tag = api('GET', 'https://api.github.com/repos/Simiely/android-adskip/git/refs/tags/v1.0')
tag_obj = api('GET', tag['object']['url'])
if 'tree' not in tag_obj: tag_obj = api('GET', tag_obj['object']['url'])
tree = api('GET', f'https://api.github.com/repos/Simiely/android-adskip/git/trees/{tag_obj["tree"]["sha"]}?recursive=1')

ref = api('GET', 'https://api.github.com/repos/Simiely/android-adskip/git/refs/heads/main')
current_tree = api('GET', ref['object']['url'])['tree']['sha']

# Restore ALL v1.0 source files EXCEPT build.gradle.kts (keep signing + no-minify)
source_items = []
for item in tree['tree']:
    if item['path'].endswith('.kt') or item['path'].endswith('.xml') or item['path'].endswith('.pro'):
        if item['path'] == 'app/build.gradle.kts':
            continue  # keep our fixed build.gradle.kts
        content = raw(item['url'])
        blob = api('POST', 'https://api.github.com/repos/Simiely/android-adskip/git/blobs', {'content': content, 'encoding': 'utf-8'})
        source_items.append({'path': item['path'], 'mode': item['mode'], 'type': 'blob', 'sha': blob['sha']})
        print(f'Restored: {item["path"]}')

# Delete all files not in v1.0
current_full = api('GET', f'https://api.github.com/repos/Simiely/android-adskip/git/trees/{current_tree}?recursive=1')
for item in current_full['tree']:
    if item['path'] not in {x['path'] for x in tree['tree']}:
        if item['path'].endswith('.kt') or item['path'].endswith('.xml'):
            source_items.append({'path': item['path'], 'mode': item['mode'], 'type': 'blob', 'sha': None})
            print(f'Deleted: {item["path"]}')

new_tree = api('POST', 'https://api.github.com/repos/Simiely/android-adskip/git/trees', {
    'base_tree': current_tree,
    'tree': source_items
})
cm = api('POST', 'https://api.github.com/repos/Simiely/android-adskip/git/commits', {
    'message': 'revert: pure v1.0 baseline + signing',
    'tree': new_tree['sha'],
    'parents': [ref['object']['sha']]
})
api('PATCH', 'https://api.github.com/repos/Simiely/android-adskip/git/refs/heads/main', {'sha': cm['sha']})
print(f'DONE: {cm["sha"][:8]} - {len(source_items)} changes')
