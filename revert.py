import urllib.request, json, ssl

token = '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'
ctx = ssl.create_default_context()

def api(method, url, data=None):
    req = urllib.request.Request(url, method=method)
    req.add_header('Authorization', f'Bearer {token}')
    req.add_header('Accept', 'application/vnd.github+json')
    if data: req.add_header('Content-Type', 'application/json'); req.data = json.dumps(data).encode()
    with urllib.request.urlopen(req, context=ctx) as r: return json.loads(r.read())

ref = api('GET', 'https://api.github.com/repos/Simiely/android-adskip/git/refs/heads/main')
current_tree = api('GET', ref['object']['url'])['tree']['sha']

v10_items = [
    {'path': 'app/build.gradle.kts', 'mode': '100644', 'type': 'blob', 'sha': '189fed045d9624122330e4dd072e3b7300b571ec'},
    {'path': 'app/proguard-rules.pro', 'mode': '100644', 'type': 'blob', 'sha': 'b71b8d404c88fca91ba1e27195a1d2bda7ebcc34'},
    {'path': 'app/src/main/java/com/simiely/adskip/float/FloatWindowManager.kt', 'mode': '100644', 'type': 'blob', 'sha': 'e90f0ce0aeedf76e260ca14ec32c2f081868dc9c'},
    {'path': 'app/src/main/java/com/simiely/adskip/service/AdSkipAccessibilityService.kt', 'mode': '100644', 'type': 'blob', 'sha': 'db1ec66e09fa84c0c10a80853aa09b4659f47290'},
    {'path': 'app/src/main/java/com/simiely/adskip/service/KeepAliveService.kt', 'mode': '100644', 'type': 'blob', 'sha': 'f9d6bd4d8498cd5bb54a8a5f14c11a37d316be7c'},
    {'path': 'app/src/main/java/com/simiely/adskip/ui/MainActivity.kt', 'mode': '100644', 'type': 'blob', 'sha': '952a918b0623445e1cd9f1025bf90a2c98913b3f'},
    {'path': 'app/src/main/java/com/simiely/adskip/util/SecurePrefs.kt', 'mode': '100644', 'type': 'blob', 'sha': '52836176aa18f2250ea3b67da193e6a3e47207b2'},
    {'path': 'app/src/main/res/layout/activity_main.xml', 'mode': '100644', 'type': 'blob', 'sha': 'ab5cf15470cf8350654bfa27fd0188e9a1a40aba'},
]

new_tree = api('POST', 'https://api.github.com/repos/Simiely/android-adskip/git/trees', {
    'base_tree': current_tree,
    'tree': v10_items
})

cm = api('POST', 'https://api.github.com/repos/Simiely/android-adskip/git/commits', {
    'message': 'revert: restore core files to v1.0 working versions',
    'tree': new_tree['sha'],
    'parents': [ref['object']['sha']]
})

api('PATCH', 'https://api.github.com/repos/Simiely/android-adskip/git/refs/heads/main', {'sha': cm['sha']})
print(f'DONE: {cm["sha"][:8]}')
