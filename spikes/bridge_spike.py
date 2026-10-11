"""Phase 0 spike: stand-in for the Minecraft side of the bridge.
Measures UDP round trip through the CS2 game tick, fetches live cvars + weapon VData,
and times a cvar change made over netcon until its push arrives on the bridge socket."""
import json, socket, statistics, sys, time, os

BRIDGE = ('127.0.0.1', 29016)
NETCON = ('127.0.0.1', 29015)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
os.makedirs(OUT, exist_ok=True)

u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
u.bind(('127.0.0.1', 0))
u.settimeout(1.0)

def req(msg, expect):
    u.sendto(msg.encode(), BRIDGE)
    while True:
        d = u.recv(65535).decode()
        if d.startswith(expect):
            return d[len(expect):].strip()

# 1. latency: ping through OnTick
rtts, ticks = [], []
for i in range(400):
    t = time.perf_counter()
    u.sendto(f'ping {i}'.encode(), BRIDGE)
    while True:
        d = u.recv(256).decode().split()
        if d[0] == 'pong' and d[1] == str(i):
            break
    rtts.append((time.perf_counter() - t) * 1000)
    ticks.append(int(d[2]))
    time.sleep(0.0037)  # deliberately not aligned to the 15.625 ms tick
q = statistics.quantiles(rtts, n=100)
print(f'RTT via OnTick over {len(rtts)} pings: min {min(rtts):.2f}  p50 {q[49]:.2f}  p95 {q[94]:.2f}  p99 {q[98]:.2f}  max {max(rtts):.2f} ms')
dt = (time.perf_counter())
print(f'server ticks advanced: {ticks[-1]-ticks[0]}')

# 2. live cvars
cv = json.loads(req('cvars', 'cvars '))
print('cvars:', json.dumps(cv, indent=1))
json.dump(cv, open(os.path.join(OUT, 'cvars.json'), 'w'), indent=1)

# 3. weapon VData
for w in sys.argv[1:] or ['weapon_ak47']:
    u.sendto(f"vdata {w}".encode(), BRIDGE); vd = u.recv(65535).decode(); vd = vd[6:] if vd.startswith("vdata ") else vd
    try:
        j = json.loads(vd)
        json.dump(j, open(os.path.join(OUT, f'{w}.json'), 'w'), indent=1)
        keys = ['MaxClip1', 'CycleTime', 'Damage', 'ArmorRatio', 'HeadshotMultiplier', 'Penetration', 'Range',
                'RangeModifier', 'RecoilSeed', 'SpreadSeed', 'RecoilMagnitude', 'RecoilAngle', 'Spread',
                'InaccuracyStand', 'InaccuracyMove', 'InaccuracyJump', 'MaxSpeed', 'IsFullAuto']
        print(w, len(j), 'fields;', {k: j.get(k) for k in keys})
    except Exception:
        print(w, 'RAW:', vd[:500])

# 4. cvar push latency: change sv_airaccelerate over netcon, wait for bridge push
print(req('sub', 'subbed') or 'subscribed')
n = socket.create_connection(NETCON, timeout=2)
orig = cv['sv_airaccelerate']
for val in ['100', orig]:
    t = time.perf_counter()
    n.sendall(f'sv_airaccelerate {val}\n'.encode())
    while True:
        d = u.recv(256).decode()
        if d.startswith('cvar sv_airaccelerate'):
            print(f'push "{d}" after {(time.perf_counter()-t)*1000:.2f} ms')
            break
n.close()
