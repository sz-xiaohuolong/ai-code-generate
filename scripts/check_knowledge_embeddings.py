"""检查已安装的本地模型；不下载、不修改业务索引。质量未通过退出 1，依赖故障退出 2。"""
import argparse
import json
import math
import sys
import time
import urllib.request
from pathlib import Path


def request(path, body=None):
    payload = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(
        'http://127.0.0.1:11434' + path, data=payload,
        headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=120) as response:
        return json.load(response)


def score(a, b):
    if len(a) != len(b):
        raise ValueError('模型返回的向量维度不一致')
    cosine = sum(x * y for x, y in zip(a, b)) / math.sqrt(sum(x * x for x in a) * sum(y * y for y in b))
    return (cosine + 1) / 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', choices=['bge-m3:latest', 'nomic-embed-text:latest'], default='bge-m3:latest')
    parser.add_argument('--threshold', type=float, default=0.78, help='固定阈值；不使用保留集自动调参')
    parser.add_argument('--output', type=Path, required=True, help='报告路径，不应覆盖已有证据')
    args = parser.parse_args()
    if not math.isfinite(args.threshold) or not 0 <= args.threshold <= 1:
        parser.error('阈值必须在 0～1 之间')
    if args.output.exists():
        parser.error('报告已存在，请指定新路径以保留历史证据')
    installed = {m['name']: m for m in request('/api/tags')['models']}
    model = installed.get(args.model)
    if model is None or model.get('remote_host') or model.get('remote_model'):
        raise ValueError('指定模型未安装或不是本地模型')
    fixture = Path(__file__).resolve().parent.parent / 'src/test/resources/knowledge/embedding-quality.json'
    data = json.loads(fixture.read_text(encoding='utf-8'))

    def embed(text, query):
        prefix = ('search_query: ' if query else 'search_document: ') if args.model.startswith('nomic-embed-text') else ''
        vector = request('/api/embed', {'model': args.model, 'input': prefix + text, 'truncate': False})['embeddings'][0]
        if not vector or any(not math.isfinite(x) for x in vector) or sum(x * x for x in vector) == 0:
            raise ValueError('向量无效')
        return vector

    started = time.monotonic()
    vectors = [embed(d, False) for d in data['documents']]
    rows = []
    for case in data['cases']:
        vector = embed(case['query'], True)
        scores = [score(vector, document) for document in vectors]
        top = scores.index(max(scores))
        expected = case['expected']
        passed = max(scores) < args.threshold if expected is None else top == expected and scores[expected] >= args.threshold
        rows.append(dict(**case, scores=scores, top=top, passed=passed))
    after = {m['name']: m['digest'] for m in request('/api/tags')['models']}
    if after.get(args.model) != model['digest']:
        raise ValueError('测量期间模型摘要变化，结果无效')
    summary = {group: {'passed': sum(r['passed'] for r in rows if r['group'] == group),
                       'total': sum(r['group'] == group for r in rows)} for group in ('calibration', 'holdout')}
    report = dict(model=args.model, digest=model['digest'], dimensions=len(vectors[0]),
                  threshold=args.threshold, elapsed_seconds=time.monotonic() - started,
                  documents=data['documents'], results=rows, summary=summary)
    with args.output.open('x', encoding='utf-8') as output:
        output.write(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(summary, ensure_ascii=False))
    for row in rows:
        if not row['passed']:
            print('未通过：' + row['query'])
    return 0 if all(row['passed'] for row in rows) else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, IndexError, TypeError) as error:
        print('检查未完成：' + str(error), file=sys.stderr)
        sys.exit(2)
