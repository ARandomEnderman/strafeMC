"""Sample bot pawns at ~64 Hz while bots run around, and measure ground acceleration per tick and
top speed in each mode (run / crouch / walk) against weapon max speed. Derives the hidden movement
multipliers from the real engine instead of from memory."""
import json, socket, time, collections, sys
u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); u.bind(('127.0.0.1', 0)); u.settimeout(2)
n = socket.create_connection(('127.0.0.1', 29015), timeout=2)
def con(c): n.sendall((c + '\n').encode()); time.sleep(0.2)
def probe():
    u.sendto(b'probe', ('127.0.0.1', 29016))
    while True:
        d = u.recv(65535).decode()
        if d.startswith('probe '): return json.loads(d[6:])

def run(mode, secs):
    prev = {}; lastpos = {}; top = collections.defaultdict(float); accels = []; seen_ms = {}
    end = time.time() + secs
    while time.time() < end:
        for b in probe():
            k = b['name']; seen_ms[k] = (b['maxspeed'], b['weapon'])
            # AbsVelocity reads 0 on bots; derive speed from origin delta over exactly one tick
            o = b['origin']; last = lastpos.get(k); lastpos[k] = (b['tick'], o)
            if not last or b['tick'] != last[0] + 1: continue
            sp = ((o[0]-last[1][0])**2 + (o[1]-last[1][1])**2) ** .5 * 64
            if b['onGround']: top[(k, b['weapon'])] = max(top[(k, b['weapon'])], sp)
            if k in prev:
                pt, psp, pg = prev[k]
                if b['tick'] == pt + 1 and pg and b['onGround'] and sp > psp + 0.5 and psp < 150:
                    accels.append((sp - psp, b['weapon'], b['ducked']))
            prev[k] = (b['tick'], sp, b['onGround'])
        time.sleep(0.002)
    print(f'== {mode}')
    for (k, w), s in sorted(top.items()): print(f'   top ground speed {k:10} {w:18} {s:7.2f}')
    c = collections.Counter(round(a, 2) for a, w, d in accels)
    print('   per-tick ground speed increments (most common):', c.most_common(6))

con('sv_cheats 1'); con('bot_quota 2'); con('mp_warmup_end'); con('mp_freezetime 0'); con('mp_roundtime 60'); con('bot_dont_shoot 1')
con('mp_restartgame 1'); time.sleep(3)
con('bot_crouch 0'); con('bot_walk 0'); run('run', 20)
con('bot_walk 1'); run('walk', 20)
con('bot_walk 0'); con('bot_crouch 1'); run('crouch', 20)
con('bot_crouch 0'); con('bot_quota 0'); con('sv_cheats 0')
