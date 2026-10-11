"""Measure CS2 fall damage in the real engine: lift a bot by dz units (zero velocity), let it fall, read health loss.
Impact speed for a drop from rest: v = sqrt(2 * sv_gravity * dz)."""
import json, socket, time, math, sys
u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); u.bind(('127.0.0.1', 0)); u.settimeout(2)
n = socket.create_connection(('127.0.0.1', 29015), timeout=2)


def con(c): n.sendall((c + '\n').encode()); time.sleep(0.4)


def req(msg, verb):
    u.sendto(msg.encode(), ('127.0.0.1', 29016))
    while True:
        d = u.recv(65535).decode()
        if d.startswith(verb + ' '): return d[len(verb) + 1:]


def bot():
    for b in json.loads(req('probe', 'probe')):
        if b.get('alive'): return b
    return None


con('bot_quota 1'); con('mp_warmup_end'); con('mp_freezetime 0'); con('mp_roundtime 60'); con('mp_ignore_round_win_conditions 1')
con('bot_stop 1'); con('mp_restartgame 1'); time.sleep(4)
g = 800.0
rows = []
for dz in [150, 200, 250, 300, 350, 400, 450, 500, 600, 700, 800, 1000]:
    b = None
    for _ in range(20):
        b = bot()
        if b: break
        con('mp_restartgame 1'); time.sleep(4)
    print(req(f'liftbot {dz}', 'liftbot'))
    time.sleep(2.5)
    b = bot()
    hp = b['health'] if b else 0
    v = math.sqrt(2 * g * dz)
    rows.append((dz, v, 100 - hp))
    print(f'drop {dz:5} u  impact {v:7.1f} u/s  damage {100 - hp}')
    if not b or hp <= 0:
        con('mp_restartgame 1'); time.sleep(4)
con('bot_kick'); con('bot_quota 0'); con('bot_stop 0'); con('mp_ignore_round_win_conditions 0')
json.dump(rows, open(r'C:\Users\are\cs2mc\spikes\out\fall_damage.json', 'w'))
