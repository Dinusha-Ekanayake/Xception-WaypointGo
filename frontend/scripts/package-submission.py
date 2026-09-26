"""Create a source-only handoff archive with deterministic paths, excluding local state."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
output = root / 'artifacts' / 'waypoint-hackathon-source.zip'
output.parent.mkdir(exist_ok=True)
folders = ['app', 'components', 'lib', 'public', 'data', 'docs', 'scripts', 'tests', 'migrations']
files = ['package.json', 'package-lock.json', 'tsconfig.json', 'next.config.mjs',
         'postcss.config.mjs', 'playwright.config.ts', 'Dockerfile', 'compose.yaml',
         '.dockerignore', '.gitignore', '.env.example', 'README.md']
selected = [root / p for p in files]
for folder in folders:
    selected.extend(p for p in (root / folder).rglob('*') if p.is_file())
selected = [p for p in selected if p.name != 'sw.js' and '__pycache__' not in p.parts]
with ZipFile(output, 'w', ZIP_DEFLATED) as archive:
    for p in sorted(selected):
        archive.write(p, p.relative_to(root))
with ZipFile(output) as archive:
    assert archive.testzip() is None
    names = archive.namelist()
    assert 'data/General Data/outlets.csv' in names
    assert 'compose.yaml' in names
    assert not any(n.endswith(('.db', '.sqlite')) or n.startswith(('var/', '.git/', 'node_modules/')) for n in names)
print(f'{output}: {len(selected)} files, {output.stat().st_size:,} bytes; integrity verified')
