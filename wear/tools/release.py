"""Publish a verified, private-data-free WordWatch APK and its usable update manifest.

Uses GH_TOKEN from the process environment or the existing Git credential helper in memory.
Does not create credentials, print them, overwrite releases, or change the signing key.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import zipfile

REPO = '121103qwq/english-word-review'


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def verify_public_apk(path, aapt, apksigner, certificate):
    with zipfile.ZipFile(path) as apk:
        if json.loads(apk.read('assets/bootstrap.json')) != {}:
            raise ValueError('Public APK must have an empty bootstrap asset')
        for name in apk.namelist():
            if name.endswith(('.json', '.csv', '.xml', '.txt')):
                if re.search(rb'github_pat_[A-Za-z0-9_]{40,}|gh[pousr]_[A-Za-z0-9]{30,}', apk.read(name)):
                    raise ValueError('Credential-like data in APK')
    signed = subprocess.run([str(apksigner), 'verify', '--verbose', '--print-certs', str(path)], capture_output=True, text=True, check=True)
    actual = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})', signed.stdout)
    if len(actual) != 1 or actual[0].lower() != certificate.lower():
        raise ValueError('APK signer does not match the explicitly expected certificate')
    badging = subprocess.run([str(aapt), 'dump', 'badging', str(path)], capture_output=True, check=True).stdout.decode('utf-8', errors='replace')
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    sdk = re.search(r"sdkVersion:'(\d+)'", badging)
    if package is None or package[1] != 'cn.wordwatch.app' or sdk is None:
        raise ValueError('Unexpected APK identity')
    return {'packageName': package[1], 'versionCode': int(package[2]), 'versionName': package[3], 'minSdk': int(sdk[1])}


def credential():
    token = os.environ.get('GH_TOKEN')
    if token:
        return token
    result = subprocess.run(['git', '-c', 'credential.interactive=never', 'credential', 'fill'],
                            input='protocol=https\nhost=github.com\n\n', capture_output=True, text=True, check=True)
    fields = dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)
    return fields['password']


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('Unexpected credentialed redirect')


def request(token, method, path, body=None, raw=None, content_type='application/json'):
    url = path if path.startswith('https://uploads.github.com/') else 'https://api.github.com/' + path
    if not (url.startswith('https://api.github.com/repos/' + REPO + '/') or url.startswith('https://uploads.github.com/repos/' + REPO + '/')):
        raise ValueError('Unexpected release destination')
    payload = raw if raw is not None else json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=payload, method=method,
        headers={'Authorization': 'Bearer ' + token, 'User-Agent': 'WordWatch-Release',
                 'Accept': 'application/vnd.github+json', 'Content-Type': content_type, 'X-GitHub-Api-Version': '2022-11-28'})
    with urllib.request.build_opener(NoRedirect()).open(req, timeout=90) as response:
        return json.load(response)


def publish(args):
    apk = Path(args.apk).resolve()
    identity = verify_public_apk(apk, args.aapt, args.apksigner, args.certificate)
    if not re.fullmatch(r'[a-f0-9]{40}', args.target_sha):
        raise ValueError('A concrete source commit is required')
    tag = 'wear-v' + identity['versionName']
    if not re.fullmatch(r'wear-v[0-9]+\.[0-9]+\.[0-9]+', tag):
        raise ValueError('Stable semantic version required')
    token = credential()
    prefix = 'repos/' + REPO
    try:
        release = request(token, 'GET', prefix + '/releases/tags/' + tag)
        if not release['draft']:
            raise ValueError('Release already published; keep it immutable and use a new version')
        if release['target_commitish'] != args.target_sha:
            raise ValueError('Draft source commit differs; review it before publishing')
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
        release = request(token, 'POST', prefix + '/releases', {'tag_name': tag, 'target_commitish': args.target_sha,
            'name': '词序 ' + identity['versionName'], 'body': Path(args.notes).read_text(encoding='utf-8'), 'draft': True, 'prerelease': False})

    def upload(name, data, mime):
        for old in release.get('assets', []):
            if old['name'] == name:
                if old.get('digest') == 'sha256:' + hashlib.sha256(data).hexdigest():
                    return old
                raise ValueError('Existing draft asset differs or cannot be verified; no overwrite performed')
        url = 'https://uploads.github.com/repos/' + REPO + '/releases/' + str(release['id']) + '/assets?name=' + urllib.parse.quote(name)
        return request(token, 'POST', url, raw=data, content_type=mime)

    asset = upload('WordWatch-' + identity['versionName'] + '.apk', apk.read_bytes(), 'application/vnd.android.package-archive')
    manifest = dict(schemaVersion=1, channel='stable', **identity, apkAssetId=asset['id'], apkSize=apk.stat().st_size,
                    apkSha256=sha256(apk), sourceCommit=args.target_sha, supportedPackageSchemas=[1, 2],
                    notes=Path(args.notes).read_text(encoding='utf-8').strip().split('\n\n', 1)[0][:1000])
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode()
    upload('wordwatch-update.json', manifest_bytes, 'application/json')
    if args.source:
        source = Path(args.source)
        upload('WordWatch-' + identity['versionName'] + '-source.zip', source.read_bytes(), 'application/zip')
    published = request(token, 'PATCH', prefix + '/releases/' + str(release['id']), {'draft': False, 'make_latest': 'true'})
    if args.manifest_out:
        Path(args.manifest_out).write_bytes(manifest_bytes)
    print(json.dumps({'url': published['html_url'], 'tag': tag, 'apkSha256': manifest['apkSha256'], 'apkAssetId': asset['id']}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['apk', 'aapt', 'apksigner', 'certificate', 'target-sha', 'notes']:
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--source')
    parser.add_argument('--manifest-out')
    publish(parser.parse_args())


if __name__ == '__main__':
    try:
        main()
    except urllib.error.HTTPError as exc:
        raise SystemExit('GitHub release request failed: HTTP ' + str(exc.code)) from None
