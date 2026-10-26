#!/usr/bin/env python3
"""Download a Docker Hub image as a verified offline Docker archive."""
from pathlib import Path
import argparse
import gzip
import hashlib
import json
import re
import shutil
import tarfile
import tempfile
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('image', help='Image name and fixed tag, e.g. nginx:1.28-alpine')
    parser.add_argument('--arch', choices=['amd64', 'arm64'], default='amd64')
    parser.add_argument('--output', type=Path, required=True)
    arguments = parser.parse_args()
    if not re.fullmatch(r'[a-z0-9-]+(?:/[a-z0-9_.-]+)?:[A-Za-z0-9_.-]+', arguments.image):
        parser.error('A Docker Hub image with an explicit tag is required')
    if arguments.output.exists():
        parser.error('Output already exists; existing archives are never overwritten')
    lock = json.loads((Path(__file__).resolve().parents[1] / 'image-digests.json').read_text())
    if arguments.image not in lock:
        parser.error('Image is not registered in deploy/image-digests.json; review and pin it first')
    pinned = lock[arguments.image]
    name, tag = arguments.image.split(':', 1)
    repository = name if '/' in name else 'library/' + name
    token_url = 'https://auth.docker.io/token?' + urllib.parse.urlencode({'service': 'registry.docker.io', 'scope': 'repository:' + repository + ':pull'})
    with urllib.request.urlopen(token_url, timeout=60) as response:
        token = json.load(response)['token']
    base = 'https://registry-1.docker.io/v2/' + repository
    accepted = ','.join(['application/vnd.oci.image.index.v1+json', 'application/vnd.docker.distribution.manifest.list.v2+json', 'application/vnd.oci.image.manifest.v1+json', 'application/vnd.docker.distribution.manifest.v2+json'])

    def get_manifest(reference):
        request = urllib.request.Request(base + '/manifests/' + reference, headers={'Authorization': 'Bearer ' + token, 'Accept': accepted})
        with urllib.request.urlopen(request, timeout=60) as response:
            data = response.read()
            digest = response.headers.get('Docker-Content-Digest')
        actual = 'sha256:' + hashlib.sha256(data).hexdigest()
        if digest and actual != digest:
            raise RuntimeError('Manifest checksum mismatch')
        if reference.startswith('sha256:') and actual != reference:
            raise RuntimeError('Referenced manifest checksum mismatch')
        return json.loads(data), actual

    manifest, digest = get_manifest(pinned['digest'])
    if 'manifests' in manifest:
        selected = next((entry for entry in manifest['manifests'] if entry.get('platform', {}).get('os') == 'linux' and entry.get('platform', {}).get('architecture') == arguments.arch), None)
        if selected is None:
            raise RuntimeError('Requested Linux architecture is not available')
        manifest, digest = get_manifest(selected['digest'])
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    if manifest['config']['digest'] != pinned['configs'][arguments.arch]:
        raise RuntimeError('Image config differs from reviewed digest')
    receipt = {'image': arguments.image, 'architecture': arguments.arch, 'index_digest': pinned['digest'],
               'manifest_digest': digest, 'config_digest': manifest['config']['digest'], 'layers': []}
    with tempfile.TemporaryDirectory(prefix='qdata-image-', dir=arguments.output.parent) as temporary:
        work = Path(temporary)

        def download_blob(descriptor):
            reference = descriptor['digest']
            if not re.fullmatch(r'sha256:[0-9a-f]{64}', reference):
                raise RuntimeError('Unsupported blob digest')
            target = work / (reference.split(':')[1] + '.blob')
            request = urllib.request.Request(base + '/blobs/' + reference, headers={'Authorization': 'Bearer ' + token})
            checksum = hashlib.sha256()
            size = 0
            with urllib.request.urlopen(request, timeout=180) as response, target.open('wb') as destination:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    checksum.update(chunk)
                    size += len(chunk)
                    destination.write(chunk)
            if 'sha256:' + checksum.hexdigest() != reference or size != descriptor['size']:
                raise RuntimeError('Image blob checksum or length mismatch')
            return target

        config_blob = download_blob(manifest['config'])
        config = json.loads(config_blob.read_text())
        if config.get('architecture') != arguments.arch or config.get('os') != 'linux':
            raise RuntimeError('Image configuration architecture mismatch')
        config_name = manifest['config']['digest'].split(':')[1] + '.json'
        config_blob.rename(work / config_name)
        layers = []
        for ordinal, descriptor in enumerate(manifest['layers']):
            print('Downloading verified layer', ordinal + 1, 'of', len(manifest['layers']), flush=True)
            blob = download_blob(descriptor)
            relative = descriptor['digest'].split(':')[1] + '/layer.tar'
            layer = work / relative
            layer.parent.mkdir()
            opener = gzip.open if 'gzip' in descriptor.get('mediaType', '') else open
            with opener(blob, 'rb') as source, layer.open('wb') as destination:
                shutil.copyfileobj(source, destination, 1024 * 1024)
            checksum = hashlib.sha256()
            with layer.open('rb') as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b''):
                    checksum.update(chunk)
            if 'sha256:' + checksum.hexdigest() != config['rootfs']['diff_ids'][ordinal]:
                raise RuntimeError('Uncompressed image layer checksum mismatch')
            blob.unlink()
            layers.append(relative)
            receipt['layers'].append(descriptor['digest'])
        (work / 'manifest.json').write_text(json.dumps([{'Config': config_name, 'RepoTags': [arguments.image], 'Layers': layers}]))
        with tarfile.open(arguments.output, 'w:gz') as archive:
            for relative in ['manifest.json', config_name] + layers:
                archive.add(work / relative, arcname=relative)
    checksum = hashlib.sha256()
    with arguments.output.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            checksum.update(chunk)
    receipt['archive_sha256'] = checksum.hexdigest()
    arguments.output.with_suffix(arguments.output.suffix + '.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('Verified offline image archive:', arguments.output)


if __name__ == '__main__':
    main()
