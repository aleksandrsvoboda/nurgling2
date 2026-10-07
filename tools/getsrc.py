import sys, struct
from fetchres import parse, M
for name in sys.argv[1:]:
    r=parse(name)
    if r is None: print(name,"MISSING"); continue
    ver,layers=r
    for ln,d in layers:
        if ln!="src": continue
        m=M(d); fver=m.b[0]; m.o=1
        nm=m.string()
        print("### %s :: %s" % (name, nm))
        print(m.b[m.o:].decode("utf8","replace"))
