"""Pull player-hull / view-offset / speed facts from live bot pawns (standing, crouched)."""
import json, socket, time, sys
u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); u.bind(('127.0.0.1', 0)); u.settimeout(2)
n = socket.create_connection(('127.0.0.1', 29015), timeout=2)
def con(c): n.sendall((c + '\n').encode()); time.sleep(0.3)
def probe():
    u.sendto(b'probe', ('127.0.0.1', 29016))
    while True:
        d = u.recv(65535).decode()
        if d.startswith('probe '): return json.loads(d[6:])
def show(tag, samples=1):
    for i in range(samples):
        for b in probe():
            sp = (b['vel'][0]**2 + b['vel'][1]**2) ** .5
            print(f"{tag:9} {b['name']:10} mins={b['mins']} maxs={b['maxs']} viewZ={b['viewZ']:.2f} onGround={b['onGround']} ducked={b['ducked']} duck={b['duckAmount']} hspeed={sp:7.2f} maxspeed={b['maxspeed']} stamina={b['stamina']} weapon={b['weapon']}")
        time.sleep(0.25)
con('sv_cheats 1'); con('mp_warmup_end'); con('bot_crouch 0'); time.sleep(2)
show('standing', 6)
con('bot_crouch 1'); time.sleep(2)
show('crouched', 6)
con('bot_crouch 0'); con('sv_cheats 0')
