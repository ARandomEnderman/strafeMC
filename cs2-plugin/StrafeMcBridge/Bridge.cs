using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using CounterStrikeSharp.API;
using CounterStrikeSharp.API.Core;
using CounterStrikeSharp.API.Modules.Cvars;
using CounterStrikeSharp.API.Modules.Memory;

namespace StrafeMc;

// Loopback-only UDP bridge between the CS2 dedicated server and the Minecraft mod.
// Protocol: one UTF-8 text datagram per message, "<verb> <payload>".
//   ping <id>            -> pong <id> <tick> <curtime>       (answered from OnTick: measures tick quantization)
//   cvars                -> cvars {json}                     (current movement/combat cvars)
//   sub                  -> subscribes sender to "cvar <name> <value>" pushes on change
//   vdata <weapon_class> -> vdata {json}                     (every readable field of the live CCSWeaponBaseVData)
public class StrafeMcBridge : BasePlugin
{
    public override string ModuleName => "strafeMC bridge";
    public override string ModuleVersion => "0.1.0";

    const int Port = 29016;
    static readonly string[] WatchedCvars =
    {
        "sv_accelerate", "sv_airaccelerate", "sv_air_max_wishspeed", "sv_friction", "sv_stopspeed",
        "sv_maxspeed", "sv_gravity", "sv_jump_impulse", "sv_stepsize", "sv_standable_normal",
        "sv_walkable_normal", "sv_maxvelocity", "sv_bounce", "sv_staminamax", "sv_staminajumpcost",
        "sv_staminalandcost", "sv_staminarecoveryrate", "sv_timebetweenducks", "sv_autobunnyhopping",
        "sv_enablebunnyhopping", "sv_accelerate_use_weapon_speed", "sv_wateraccelerate", "sv_waterfriction",
        "sv_ladder_scale_speed", "sv_ladder_angle", "sv_ladder_dampen",
        "weapon_recoil_scale", "weapon_accuracy_nospread", "weapon_recoil_decay_coefficient",
        "mp_damage_headshot_only", "host_timescale", "sv_cheats",
        "sv_bhop_time_window", "sv_step_move_vel_min", "sv_jump_spam_penalty_time", "sv_legacy_jump", "sv_jump_precision_enable",
        "sv_noclipspeed", "sv_noclipaccelerate", "sv_noclipfriction", "sv_noclipspeedscaleonshift",
        "footstep_audible_threshold", "sv_min_jump_landing_sound", "sv_footsteps",
        "sv_falldamage_scale", "sv_subtick_movement_view_angles",
        "sv_infinite_ammo",
    };

    UdpClient? _udp;
    readonly ConcurrentQueue<(string msg, IPEndPoint ep)> _inbox = new();
    readonly HashSet<IPEndPoint> _subs = new();
    readonly Dictionary<string, string> _last = new();
    Dictionary<string, string> _defaults = new();
    CancellationTokenSource _cts = new();

    public override void Load(bool hotReload)
    {
        _udp = new UdpClient(new IPEndPoint(IPAddress.Loopback, Port));
        var udp = _udp; var ct = _cts.Token;
        Task.Run(async () =>
        {
            while (!ct.IsCancellationRequested)
            {
                try
                {
                    var r = await udp.ReceiveAsync(ct);
                    if (IPAddress.IsLoopback(r.RemoteEndPoint.Address))
                        _inbox.Enqueue((Encoding.UTF8.GetString(r.Buffer), r.RemoteEndPoint));
                }
                catch (OperationCanceledException) { break; }
                catch (Exception e) { Console.WriteLine($"[strafemc] recv error: {e.Message}"); }
            }
        }, ct);
        RegisterListener<Listeners.OnTick>(OnTick);
        foreach (var n in WatchedCvars) _last[n] = ReadCvar(n);
        // The plugin loads while the server boots, before anyone can change a cvar: these are the server's defaults.
        // (A manual hot reload would re-snapshot current values; only done during development.)
        _defaults = WatchedCvars.ToDictionary(n => n, ReadCvar);
        Console.WriteLine($"[strafemc] bridge v{ModuleVersion} listening on 127.0.0.1:{Port}");
    }

    public override void Unload(bool hotReload)
    {
        _cts.Cancel();
        _udp?.Dispose();
    }

    void OnTick()
    {
        while (_inbox.TryDequeue(out var m)) Handle(m.msg, m.ep);
        if (_subs.Count == 0) return;
        foreach (var n in WatchedCvars)
        {
            var v = ReadCvar(n);
            if (v == _last[n]) continue;
            _last[n] = v;
            Console.WriteLine($"[strafemc] cvar changed {n} = {v}");
            foreach (var s in _subs) Send($"cvar {n} {v}", s);
        }
    }

    void Send(string s, IPEndPoint ep)
    {
        var b = Encoding.UTF8.GetBytes(s);
        _udp!.Send(b, b.Length, ep);
    }

    void Handle(string msg, IPEndPoint ep)
    {
        var sp = msg.IndexOf(' ');
        var verb = sp < 0 ? msg : msg[..sp];
        var arg = sp < 0 ? "" : msg[(sp + 1)..];
        try
        {
            switch (verb)
            {
                case "ping":
                {
                    var players = Utilities.GetPlayers();
                    int bots = players.Count(pl => pl.IsBot), humans = players.Count(pl => !pl.IsBot && !pl.IsHLTV);
                    Send($"pong {arg} {Server.TickCount} {Server.CurrentTime:R} {bots} {humans}", ep);
                    break;
                }
                case "sub": _subs.Add(ep); Send("subbed", ep); break;
                case "cvars": Send("cvars " + JsonSerializer.Serialize(WatchedCvars.ToDictionary(n => n, ReadCvar)), ep); break;
                case "defaults": Send("defaults " + JsonSerializer.Serialize(_defaults), ep); break;
                case "vdata": Send("vdata " + DumpWeapon(arg), ep); break;
                case "probe": Send("probe " + Probe(), ep); break;
                case "liftbot": Send("liftbot " + LiftBot(arg), ep); break;
                default: Send($"err unknown verb '{verb}'", ep); break;
            }
        }
        catch (Exception e)
        {
            Console.WriteLine($"[strafemc] {verb} failed: {e}");
            Send($"err {verb}: {e.Message}", ep);
        }
    }

    // Live state of every bot pawn: hull, view offset, velocity, flags, movement-services fields.
    static string Probe()
    {
        var list = new List<object>();
        foreach (var c in Utilities.GetPlayers())
        {
            var p = c.PlayerPawn.Value;
            if (p == null || !p.IsValid) continue;
            var ms = p.MovementServices == null ? null : new CCSPlayer_MovementServices(p.MovementServices.Handle);
            var col = p.Collision;
            list.Add(new
            {
                name = c.PlayerName, tick = Server.TickCount,
                origin = V(p.AbsOrigin), vel = V(p.AbsVelocity),
                mins = col == null ? null : V(col.Mins), maxs = col == null ? null : V(col.Maxs),
                viewZ = p.ViewOffset.Z, flags = p.Flags, onGround = (p.Flags & 1) != 0, ducked = (p.Flags & 2) != 0,
                duckAmount = ms?.DuckAmount, ducking = ms?.Ducking, maxspeed = ms?.Maxspeed, stamina = ms?.Stamina,
                weapon = p.WeaponServices?.ActiveWeapon.Value?.DesignerName,
                health = p.Health, alive = p.LifeState == 0,
            });
        }
        return JsonSerializer.Serialize(list);
    }
    // Engine measurement helper: put the first living bot `dz` units above where it stands (zero velocity), full health.
    static string LiftBot(string arg)
    {
        float dz = float.Parse(arg, System.Globalization.CultureInfo.InvariantCulture);
        foreach (var c in Utilities.GetPlayers())
        {
            if (!c.IsBot) continue;
            var p = c.PlayerPawn.Value;
            if (p == null || !p.IsValid || p.LifeState != 0 || p.AbsOrigin == null) continue;
            p.Health = 100;
            Utilities.SetStateChanged(p, "CBaseEntity", "m_iHealth");
            var o = p.AbsOrigin;
            p.Teleport(new CounterStrikeSharp.API.Modules.Utils.Vector(o.X, o.Y, o.Z + dz), null, new CounterStrikeSharp.API.Modules.Utils.Vector(0, 0, 0));
            return c.PlayerName + " lifted " + dz;
        }
        return "no living bot";
    }

    static float[]? V(CounterStrikeSharp.API.Modules.Utils.Vector? v) => v == null ? null : new[] { v.X, v.Y, v.Z };

    static string ReadCvar(string name)
    {
        var c = ConVar.Find(name);
        if (c == null) return "<missing>";
        try
        {
            return c.Type switch
            {
                ConVarType.Bool => c.GetPrimitiveValue<bool>() ? "1" : "0",
                ConVarType.Float32 => c.GetPrimitiveValue<float>().ToString("R", System.Globalization.CultureInfo.InvariantCulture),
                ConVarType.Float64 => c.GetPrimitiveValue<double>().ToString("R", System.Globalization.CultureInfo.InvariantCulture),
                ConVarType.Int32 => c.GetPrimitiveValue<int>().ToString(),
                ConVarType.UInt32 => c.GetPrimitiveValue<uint>().ToString(),
                ConVarType.Int64 => c.GetPrimitiveValue<long>().ToString(),
                ConVarType.UInt64 => c.GetPrimitiveValue<ulong>().ToString(),
                ConVarType.Int16 => c.GetPrimitiveValue<short>().ToString(),
                ConVarType.UInt16 => c.GetPrimitiveValue<ushort>().ToString(),
                _ => c.StringValue,
            };
        }
        catch (Exception e) { return $"<{c.Type}: {e.Message}>"; }
    }

    // arg = item definition index (e.g. "7") or a designer name already present on the map (e.g. "weapon_ak47").
    // No entity is spawned: spawning loose weapons + reflecting pointer fields crashed the server in the first spike.
    static string DumpWeapon(string key)
    {
        CCSWeaponBaseVData? v = null;
        if (key.All(char.IsDigit))
            v = VirtualFunctions.GetCSWeaponDataFromKey(-1, key);
        else
        {
            var w = Utilities.FindAllEntitiesByDesignerName<CCSWeaponBase>(key).FirstOrDefault();
            if (w?.VData != null) v = new CCSWeaponBaseVData(w.VData.Handle);
        }
        if (v == null || v.Handle == IntPtr.Zero) throw new Exception($"no weapon data for '{key}'");
        // Schema properties are by-ref, so read every [SchemaMember(class, member)] field straight from its schema offset.
        var o = new SortedDictionary<string, object?>();
        var skipped = new SortedDictionary<string, string>();
        for (var ty = typeof(CCSWeaponBaseVData); ty != null && ty != typeof(object); ty = ty.BaseType)
        foreach (var p in ty.GetProperties(BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly))
        {
            var attr = p.CustomAttributes.FirstOrDefault(c => c.AttributeType.Name == "SchemaMemberAttribute");
            if (attr == null || attr.ConstructorArguments.Count < 2) continue;
            var cls = (string)attr.ConstructorArguments[0].Value!;
            var mem = (string)attr.ConstructorArguments[1].Value!;
            var t = p.PropertyType.IsByRef ? p.PropertyType.GetElementType()! : p.PropertyType;
            try
            {
                var at = v.Handle + Schema.GetSchemaOffset(cls, mem);
                object? val = t switch
                {
                    _ when t == typeof(float) => BitConverter.Int32BitsToSingle(Marshal.ReadInt32(at)),
                    _ when t == typeof(int) || t == typeof(uint) => Marshal.ReadInt32(at),
                    _ when t == typeof(short) || t == typeof(ushort) => Marshal.ReadInt16(at),
                    _ when t == typeof(bool) || t == typeof(byte) => Marshal.ReadByte(at),
                    _ when t.IsEnum => Enum.ToObject(t, Marshal.SizeOf(Enum.GetUnderlyingType(t)) switch
                        { 1 => (object)Marshal.ReadByte(at), 2 => Marshal.ReadInt16(at), 8 => Marshal.ReadInt64(at), _ => Marshal.ReadInt32(at) }).ToString(),
                    _ when t.Name == "CFiringModeFloat" => new[] { BitConverter.Int32BitsToSingle(Marshal.ReadInt32(at)), BitConverter.Int32BitsToSingle(Marshal.ReadInt32(at + 4)) },
                    _ when t.Name == "CFiringModeInt" => new[] { Marshal.ReadInt32(at), Marshal.ReadInt32(at + 4) },
                    _ when t == typeof(string) => p.GetValue(v),
                    _ => null,
                };
                if (val == null) skipped[mem] = t.Name; else o[mem] = val;
            }
            catch (Exception e) { skipped[mem] = "err " + (e.InnerException ?? e).Message; }
        }
        o["__class_chain_skipped"] = skipped;
        return JsonSerializer.Serialize(o);
    }
}
