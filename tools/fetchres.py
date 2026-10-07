import sys, os, struct, re, urllib.request
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")
os.makedirs(CACHE, exist_ok=True)

def fetch(name):
    p = os.path.join(CACHE, name.replace("/", "_") + ".res")
    if os.path.exists(p):
        d = open(p,"rb").read()
        return d if d else None
    try:
        with urllib.request.urlopen("http://game.havenandhearth.com/res/"+name+".res", timeout=30) as r:
            d = r.read()
    except Exception:
        d = b""
    open(p,"wb").write(d)
    return d if d else None

class M:
    def __init__(s,b): s.b=b; s.o=0
    def bytes(s,n): r=s.b[s.o:s.o+n]; s.o+=n; return r
    def u16(s): r=struct.unpack_from("<H",s.b,s.o)[0]; s.o+=2; return r
    def i32(s): r=struct.unpack_from("<i",s.b,s.o)[0]; s.o+=4; return r
    def string(s):
        e=s.b.index(b"\0",s.o); r=s.b[s.o:e].decode("utf8","replace"); s.o=e+1; return r
    def eom(s): return s.o>=len(s.b)

def parse(name):
    d = fetch(name)
    if d is None: return None
    m=M(d)
    if m.bytes(16)!=b"Haven Resource 1": return None
    ver=m.u16(); layers=[]
    while not m.eom():
        ln=m.string(); l=m.i32(); layers.append((ln,m.bytes(l)))
    return ver,layers

def meshid(d):
    o=0; fl=d[o]; o+=1
    if fl & 0x80:
        if (fl & 0x7f)!=1: return -1
        return struct.unpack_from("<h",d,o)[0]
    o+=4
    if fl & 2: return struct.unpack_from("<h",d,o)[0]
    return -1

def describe(name):
    r=parse(name)
    if r is None: return "%-44s -- MISSING" % name
    ver,layers=r
    tt=[d.decode("utf8","replace") for ln,d in layers if ln=="tooltip"]
    ids=sorted(set(i for ln,d in layers if ln=="mesh" for i in [meshid(d)] if i>=0))
    extra=""
    if ids: extra=" stages=0..%d (ids=%s)" % (max(ids)//10, ids)
    codes=[re.findall(rb"[\x20-\x7e]{6,}",d)[:1] for ln,d in layers if ln=="codeentry"]
    codes=[c[0].decode() for c in codes if c]
    return "%-44s v%-3d %-28s%s%s" % (name, ver, ("tt=%r"%tt[0]) if tt else "", extra, (" code="+",".join(codes)) if codes else "")

for n in sys.argv[1:]:
    print(describe(n))
