"""Minimal KV3 text parser for VRF dumps (prototype of the Java one in the mod)."""
import re, sys, json

TOK = re.compile(r'\s*(?:(//[^\n]*|/\*.*?\*/)|(<!--.*?-->)|("""[\s\S]*?"""|"(?:[^"\\]|\\.)*")|([{}\[\]=,])|([^\s{}\[\]=,"]+))', re.S)


def tokens(s):
    for m in TOK.finditer(s):
        if m.group(1) or m.group(2):
            continue
        t = m.group(3) or m.group(4) or m.group(5)
        if t is not None:
            yield t


def parse(s):
    toks = list(tokens(s))
    i = 0

    def val():
        nonlocal i
        t = toks[i]; i += 1
        if t == '{':
            d = {}
            while toks[i] != '}':
                k = toks[i].strip('"'); i += 2  # key '='
                d[k] = val()
                if toks[i] == ',': i += 1
            i += 1
            return d
        if t == '[':
            a = []
            while toks[i] != ']':
                a.append(val())
                if toks[i] == ',': i += 1
            i += 1
            return a
        if t.startswith('"'):
            return t.strip('"')
        if re.fullmatch(r'-?\d+', t):
            return int(t)
        if re.fullmatch(r'-?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?', t):
            return float(t)
        if t in ('true', 'false'):
            return t == 'true'
        if t == 'null':
            return None
        if t.endswith(':') and i < len(toks) and toks[i].startswith('"'):
            v = toks[i].strip('"'); i += 1   # typed string, e.g. resource_name:"..."
            return v
        return t

    return val()


if __name__ == '__main__':
    print(json.dumps(parse(open(sys.argv[1], encoding='utf-8').read()))[:300])
