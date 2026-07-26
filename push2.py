import urllib.request, json, ssl, os

token = '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'
ctx = ssl.create_default_context()
base = r'C:\Users\Simiely\WorkBuddy\2026-07-25-10-12-07\android-adskip'

def api(method, url, data=None):
    req = urllib.request.Request(url, method=method)
    req.add_header('Authorization', f'Bearer {token}')
    req.add_header('Accept', 'application/vnd.github+json')
    if data: req.add_header('Content-Type', 'application/json'); req.data = json.dumps(data).encode()
    with urllib.request.urlopen(req, context=ctx) as r: return json.loads(r.read())

ref = api('GET', 'https://api.github.com/repos/Simiely/android-adskip/git/refs/heads/main')
current_tree = api('GET', ref['object']['url'])['tree']['sha']

path = '.github/workflows/build.yml'
with open(os.path.join(base, path), 'r', encoding='utf-8') as f:
    content = f.read()

blob = api('POST', 'https://api.github.com/repos/Simiely/android-adskip/git/blobs', {'content': content, 'encoding': 'utf-8'})
new_tree = api('POST', 'https://api.github.com/repos/Simiely/android-adskip/git/trees', {
    'base_tree': current_tree,
    'tree': [{'path': path, 'mode': '100644', 'type': 'blob', 'sha': blob['sha']}]
})
cm = api('POST', 'https://api.github.com/repos/Simiely/android-adskip/git/commits', {
    'message': 'fix: use *.apk wildcard for artifact',
    'tree': new_tree['sha'],
    'parents': [ref['object']['sha']]
})
api('PATCH', 'https://api.github.com/repos/Simiely/android-adskip/git/refs/heads/main', {'sha': cm['sha']})
print(f'PUSHED: {cm["sha"][:8]}')
