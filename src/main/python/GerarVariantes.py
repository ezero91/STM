#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gerar_variantes.py - passo 1 do pipeline SBTNDM (MATSim + NSGA-II)

A partir de uma rede de transporte público inicial (schedule.xml + vehicles.xml do
MATSim) gera K redes alternativas por mutação. A rede viária (network.xml[.gz]) é usada
só como grafo para roteamento e NÃO é alterada. A frota (vehicles.xml) é mantida fixa.

Modelo de dados esperado (o dos seus arquivos): cada <transitRoute> é uma VIAGEM com
uma única partida; viagens com a mesma linha, paradas e links formam um PADRÃO
(linha x sentido). Os veículos fazem várias viagens em sequência (bloco do veículo).

Mutações (cada uma age sobre um padrão inteiro, ou só numa janela horária dele):
  reroute_trecho      troca o caminho entre duas paradas consecutivas por outro na rede viária
  insere_parada       insere uma parada existente cujo link já está sobre a rota
  remove_parada       remove uma parada intermediária
  trunca_rota         encurta o fim da rota (retorno antecipado / short-turn)
  estende_rota        prolonga o fim da rota até uma parada próxima
  desloca_bloco       desloca no tempo todas as viagens de um veículo
  reduz_frequencia    remove 1 em cada 2 ou 3 viagens de um padrão (numa janela)
  aumenta_frequencia  insere viagens nos maiores intervalos, usando veículos ociosos da linha
Mudanças de percurso são acompanhadas de um reparo dos blocos: se uma viagem alongada
passar a sobrepor a próxima viagem do mesmo veículo, as seguintes são adiadas (até um limite).

Uso:
  python gerar_variantes.py --network network.xml.gz --schedule schedule.xml \
         --vehicles vehicles.xml --n 5 --seed 42 --out variantes/

Somente biblioteca padrão (Python >= 3.9).
"""
from __future__ import annotations

import argparse
import copy
import gzip
import heapq
import itertools
import json
import math
import random
import shutil
import statistics
import time
import xml.etree.ElementTree as ET
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import TextIO, cast

INF = float("inf")


# ----------------------------------------------------------------------------
# Utilidades
# ----------------------------------------------------------------------------
def open_bin(path, mode="rb"):
    return gzip.open(path, mode) if str(path).endswith(".gz") else open(path, mode)


def open_txt(path, mode="wt"):
    if str(path).endswith(".gz"):
        return gzip.open(path, mode, encoding="utf-8")
    return open(path, mode, encoding="utf-8")


def to_s(t):
    """'HH:MM:SS' (ou número) -> segundos."""
    if t is None:
        return None
    if ":" not in t:
        return float(t)
    h, m, s = t.split(":")
    return int(h) * 3600 + int(m) * 60 + float(s)


def to_hms(s):
    s = int(round(s))
    return f"{s // 3600:02d}:{s % 3600 // 60:02d}:{s % 60:02d}"


def local(tag):
    return tag.rsplit("}", 1)[-1]


def t_in(s):
    """Instante (offset) de chegada à parada."""
    return s.arr if s.arr is not None else s.dep


def t_out(s):
    """Instante (offset) de saída da parada."""
    return s.dep if s.dep is not None else s.arr


# ----------------------------------------------------------------------------
# Rede viária (grafo para roteamento)
# ----------------------------------------------------------------------------
class RoadGraph:
    def __init__(self):
        self.link = {}                   # id -> (from, to, length_m, freespeed_m/s)
        self.out = defaultdict(list)     # nó -> links usáveis para roteamento

    @classmethod
    def load(cls, path, modes):
        g, modes = cls(), set(modes)
        with open_bin(path) as f:
            for _, el in ET.iterparse(f):
                if el.tag == "link":
                    fr, to = el.get("from"), el.get("to")
                    length, freespeed = el.get("length"), el.get("freespeed")
                    if length is None or freespeed is None:
                        raise ValueError(f"Link {el.get('id')} is missing length or freespeed")
                    g.link[el.get("id")] = (fr, to, float(length),
                                            max(float(freespeed), 0.1))
                    if modes & set(el.get("modes", "").split(",")):
                        g.out[fr].append(el.get("id"))
                    el.clear()
                elif el.tag == "node":
                    el.clear()
        return g

    def tt(self, lid):
        """Tempo em fluxo livre (s)."""
        _, _, length, fs = self.link[lid]
        return length / fs

    def tt_path(self, lids):
        return sum(self.tt(l) for l in lids)

    def km(self, lids):
        return sum(self.link[l][2] for l in lids) / 1000.0

    def path(self, src, dst, penal=None, factor=1.0, max_cost=INF):
        """Dijkstra do nó `src` ao nó `dst`. Custo = tempo em fluxo livre; links em
        `penal` custam `factor` vezes mais (para forçar caminhos alternativos).
        Retorna a lista de links (vazia se src == dst) ou None se não houver caminho."""
        if src == dst:
            return []
        dist, prev, seen, pq = {src: 0.0}, {}, set(), [(0.0, src)]
        found = False
        while pq:
            d, u = heapq.heappop(pq)
            if u in seen:
                continue
            if u == dst:
                found = True
                break
            if d > max_cost:
                return None
            seen.add(u)
            for lid in self.out.get(u, ()):
                _, v, length, fs = self.link[lid]
                nd = d + (length / fs) * (factor if penal and lid in penal else 1.0)
                if nd < dist.get(v, INF):
                    dist[v], prev[v] = nd, lid
                    heapq.heappush(pq, (nd, v))
        if not found:
            return None
        res, v = [], dst
        while v != src:
            lid = prev[v]
            res.append(lid)
            v = self.link[lid][0]
        return res[::-1]


# ----------------------------------------------------------------------------
# Modelo da rede de transporte (transitSchedule)
# ----------------------------------------------------------------------------
@dataclass
class RStop:
    ref: str
    arr: float | None
    dep: float | None
    await_dep: str | None = None


@dataclass
class Dep:
    id: str
    time: float
    veh: str


@dataclass
class Route:
    id: str
    stops: list
    links: list
    deps: list
    mode: str | None = None
    descr: str | None = None
    extra: list = field(default_factory=list)      # elementos <attributes> preservados


@dataclass
class Line:
    id: str
    routes: list
    name: str | None = None
    extra: list = field(default_factory=list)


class Schedule:
    def __init__(self, stops, lines):
        self.stops = stops          # id -> Element <stopFacility> (compartilhado, nunca mutado)
        self.lines = lines

    def clone(self):
        return Schedule(self.stops, copy.deepcopy(self.lines))

    def routes(self):
        return [(l, r) for l in self.lines for r in l.routes]


def _required_attr(el, name):
    value = el.get(name)
    if value is None:
        raise ValueError(f"Element {el.tag!r} is missing required attribute {name!r}")
    return value


def load_schedule(path):
    with open_bin(path) as f:
        root = ET.parse(f).getroot()
    stops = {el.get("id"): el for el in root.iter("stopFacility")}
    lines = []
    for le in root.findall("transitLine"):
        line_id = le.get("id")
        if line_id is None:
            raise ValueError("transitLine is missing its required id")
        line = Line(line_id, [], le.get("name"), [e for e in le if e.tag == "attributes"])
        for re_ in le.findall("transitRoute"):
            route_id = re_.get("id")
            if route_id is None:
                raise ValueError("transitRoute is missing its required id")
            rp, rt, dp = re_.find("routeProfile"), re_.find("route"), re_.find("departures")
            st = [RStop(_required_attr(s, "refId"), to_s(s.get("arrivalOffset")),
                        to_s(s.get("departureOffset")), s.get("awaitDeparture"))
                  for s in (rp if rp is not None else [])]
            links = [_required_attr(x, "refId") for x in (rt if rt is not None else [])]
            deps = []
            for d in (dp if dp is not None else []):
                departure_time = d.get("departureTime")
                if departure_time is None:
                    raise ValueError(f"Departure {d!r} is missing its required departureTime")
                dep_time = to_s(departure_time)
                if dep_time is None:
                    raise ValueError(f"Departure {d!r} has invalid departureTime {departure_time!r}")
                deps.append(Dep(_required_attr(d, "id"), dep_time, _required_attr(d, "vehicleRefId")))
            line.routes.append(Route(route_id, st, links, deps,
                                     re_.findtext("transportMode"), re_.findtext("description"),
                                     [e for e in re_ if e.tag == "attributes"]))
        lines.append(line)
    return Schedule(stops, lines)


def write_schedule(sch, path):
    root = ET.Element("transitSchedule")
    ET.SubElement(root, "transitStops").extend(sch.stops.values())
    for line in sch.lines:
        le = ET.SubElement(root, "transitLine", {"id": line.id})
        if line.name is not None:
            le.set("name", line.name)
        le.extend(line.extra)
        for r in line.routes:
            re_ = ET.SubElement(le, "transitRoute", {"id": r.id})
            re_.extend(r.extra)
            if r.descr is not None:
                ET.SubElement(re_, "description").text = r.descr
            ET.SubElement(re_, "transportMode").text = r.mode or "bus"
            rp = ET.SubElement(re_, "routeProfile")
            for s in r.stops:
                a = {"refId": s.ref}
                if s.arr is not None:
                    a["arrivalOffset"] = to_hms(s.arr)
                if s.dep is not None:
                    a["departureOffset"] = to_hms(s.dep)
                if s.await_dep is not None:
                    a["awaitDeparture"] = s.await_dep
                ET.SubElement(rp, "stop", a)
            rt = ET.SubElement(re_, "route")
            for l in r.links:
                ET.SubElement(rt, "link", {"refId": l})
            dp = ET.SubElement(re_, "departures")
            for d in sorted(r.deps, key=lambda d: d.time):
                ET.SubElement(dp, "departure", {"id": d.id, "departureTime": to_hms(d.time),
                                                "vehicleRefId": d.veh})
    ET.indent(root, space="    ")
    with open_txt(path) as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n'
                '<!DOCTYPE transitSchedule SYSTEM '
                '"http://www.matsim.org/files/dtd/transitSchedule_v2.dtd">\n')
        f.write(ET.tostring(root, encoding="unicode"))
        f.write("\n")


def load_vehicle_types(path):
    with open_bin(path) as f:
        root = ET.parse(f).getroot()
    return {el.get("id"): el.get("type") for el in root if local(el.tag) == "vehicle"}


# ----------------------------------------------------------------------------
# Contexto, validação, blocos de veículo e estatísticas
# ----------------------------------------------------------------------------
def stop_positions(ctx, r):
    """Índice (em r.links) do link de cada parada, em ordem; None se inconsistente."""
    pos, p = [], 0
    for s in r.stops:
        l = ctx.stop_link.get(s.ref)
        if l is None:
            return None
        try:
            p = r.links.index(l, p)
        except ValueError:
            return None
        pos.append(p)
    return pos


def fix_offsets(r):
    """Garante offsets não decrescentes ao longo das paradas."""
    t = -INF
    for s in r.stops:
        if s.arr is not None:
            s.arr = max(s.arr, t)
            t = s.arr
        if s.dep is not None:
            s.dep = max(s.dep, t)
            t = s.dep


def trip_span(r, d):
    """(início, fim) absolutos, em segundos, da partida d da rota r."""
    return d.time + t_out(r.stops[0]), d.time + t_in(r.stops[-1])


def first_dep(r):
    return min(d.time for d in r.deps)


def vehicle_trips(sch, only=None, repl=None):
    """veículo -> viagens [(início, fim, rota, partida)] ordenadas. `repl` troca rotas por cópias."""
    out = defaultdict(list)
    for _, r in sch.routes():
        r = repl.get(id(r), r) if repl else r
        for d in r.deps:
            if only is None or d.veh in only:
                s, e = trip_span(r, d)
                out[d.veh].append((s, e, r, d))
    for xs in out.values():
        xs.sort(key=lambda x: (x[0], x[1]))
    return out


def count_overlaps(sch):
    n = 0
    for xs in vehicle_trips(sch).values():
        n += sum(1 for a, b in zip(xs, xs[1:]) if b[0] < a[1])
    return n


def pattern_key(l, r):
    return (l.id, tuple(s.ref for s in r.stops), tuple(r.links))


class Ctx:
    def __init__(self, g, sch, a):
        self.g, self.a = g, a
        self.stop_link, self.stop_xy, self.by_link = {}, {}, defaultdict(list)
        for sid, el in sch.stops.items():
            l = el.get("linkRefId")
            if l in g.link:
                self.stop_link[sid] = l
                self.stop_xy[sid] = (float(el.get("x")), float(el.get("y")))
                self.by_link[l].append(sid)
        self.counter = itertools.count(1)
        self.time_factor = self._estimate_time_factor(sch)
        n = self.n_deps(sch)
        self.min_trips = getattr(a, "min_trips", None) or round(0.85 * n)
        self.max_trips = getattr(a, "max_trips", None) or round(1.15 * n)
        self.line_vehicles = defaultdict(set)           # linha -> veículos que a operam
        for l, r in sch.routes():
            self.line_vehicles[l.id].update(d.veh for d in r.deps)
        # veículos que já têm viagens sobrepostas na base (não são reparados)
        self.bad_vehicles = {v for v, xs in vehicle_trips(sch).items()
                             if any(b[0] < a_[1] for a_, b in zip(xs, xs[1:]))}
        # rotas da base que já violam a validação ficam congeladas (não são mutadas)
        self.frozen = {(l.id, r.id) for l, r in sch.routes() if validate(self, r)}

    @staticmethod
    def n_deps(sch):
        return sum(len(r.deps) for _, r in sch.routes())

    def _estimate_time_factor(self, sch):
        """Razão mediana entre o tempo programado entre paradas e o tempo em fluxo livre."""
        ratios = []
        for _, r in sch.routes()[:2000]:
            pos = stop_positions(self, r)
            if not pos:
                continue
            for k in range(len(pos) - 1):
                tt = self.g.tt_path(r.links[pos[k] + 1:pos[k + 1] + 1])
                dt = t_in(r.stops[k + 1]) - t_out(r.stops[k])
                if tt > 0 and dt > 0:
                    ratios.append(dt / tt)
        return statistics.median(ratios) if ratios else 1.5

    def new_id(self):
        return next(self.counter)


def validate(ctx, r):
    """None se a rota é estruturalmente válida para o MATSim; senão, o motivo."""
    g = ctx.g
    if len(r.stops) < 2 or not r.links or not r.deps:
        return "rota incompleta"
    if any(l not in g.link for l in r.links):
        return "link inexistente na rede viária"
    for a, b in zip(r.links, r.links[1:]):
        if g.link[a][1] != g.link[b][0]:
            return "links consecutivos não conectados"
    if stop_positions(ctx, r) is None:
        return "paradas fora da rota ou fora de ordem"
    if ctx.stop_link[r.stops[0].ref] != r.links[0] or ctx.stop_link[r.stops[-1].ref] != r.links[-1]:
        return "rota não começa/termina nos links das paradas extremas"
    t = -INF
    for s in r.stops:
        for v in (s.arr, s.dep):
            if v is not None:
                if v < t:
                    return "offsets decrescentes"
                t = v
    return None


def bounds_ok(ctx, old_km, new_km):
    """Aceita se o comprimento novo está em [min_km, max_km] ou se não piorou o desvio."""
    lo, hi = ctx.a.min_km, ctx.a.max_km

    def gap(k):
        return max(0.0 if lo is None else lo - k, 0.0 if hi is None else k - hi, 0.0)

    return gap(new_km) == 0 or gap(new_km) <= gap(old_km)


def plan_shifts(ctx, sch, repl):
    """Depois de trocar rotas (`repl`: id(rota antiga) -> cópia mutada), calcula quanto adiar
    cada viagem dos veículos afetados para que não haja sobreposição com a viagem anterior
    do mesmo veículo. Retorna {id(partida): segundos} ou None se passar de max_cascade."""
    vehs = {d.veh for new in repl.values() for d in new.deps}
    shifts, cap = {}, ctx.a.max_cascade_min * 60
    for xs in vehicle_trips(sch, vehs, repl).values():
        end_prev = None
        for start, end, _, d in xs:
            s = 0 if end_prev is None else max(0, math.ceil(end_prev + ctx.a.layover_s - start))
            if s > cap:
                return None
            if s > 0:
                shifts[id(d)] = s
            end_prev = end + s
    return shifts


def stats(ctx, sch):
    g, nd, km, vkm, vh, vids, pats = ctx.g, 0, 0.0, 0.0, 0.0, set(), set()
    for l, r in sch.routes():
        k = g.km(r.links)
        nd, km, vkm = nd + len(r.deps), km + k, vkm + k * len(r.deps)
        if r.stops:
            vh += (t_in(r.stops[-1]) - t_out(r.stops[0])) / 3600 * len(r.deps)
        vids.update(d.veh for d in r.deps)
        pats.add(pattern_key(l, r))
    return {"linhas": len(sch.lines), "padroes": len(pats), "viagens": nd, "veiculos": len(vids),
            "veiculo_km": round(vkm, 1), "veiculo_h": round(vh, 1),
            "sobreposicoes_de_bloco": count_overlaps(sch)}


def _sig(r):
    return (tuple(r.links), tuple((s.ref, s.arr, s.dep) for s in r.stops),
            tuple(sorted((d.time, d.veh) for d in r.deps)))


def diff(base, sch):
    b = {(l.id, r.id): _sig(r) for l, r in base.routes()}
    s = {(l.id, r.id): _sig(r) for l, r in sch.routes()}
    return {"alteradas": sum(1 for k in s if k in b and s[k] != b[k]),
            "novas": len(set(s) - set(b)), "removidas": len(set(b) - set(s))}


def to_dict(sch):
    """Cromossomo em JSON (árvore: linhas -> rotas -> paradas/links/partidas)."""
    return {"lines": [{"id": l.id, "name": l.name, "routes": [
        {"id": r.id, "mode": r.mode,
         "stops": [{"ref": s.ref, "arr": s.arr, "dep": s.dep, "await": s.await_dep} for s in r.stops],
         "links": r.links,
         "departures": [{"id": d.id, "time": d.time, "veh": d.veh} for d in r.deps]}
        for r in l.routes]} for l in sch.lines]}


# ----------------------------------------------------------------------------
# Escolha do alvo: um padrão (linha x sentido) e um escopo (todas as viagens ou uma janela)
# ----------------------------------------------------------------------------
def pick_group(ctx, sch, rng):
    groups = defaultdict(list)
    for l, r in sch.routes():
        if (l.id, r.id) not in ctx.frozen and r.deps:
            groups[pattern_key(l, r)].append((l, r))
    if not groups:
        return None
    items = sorted(groups[rng.choice(sorted(groups))], key=lambda lr: first_dep(lr[1]))
    scope = "todas as viagens"
    if len(items) > 3 and rng.random() < 0.5:
        ts = [first_dep(r) for _, r in items]
        t0, w = rng.uniform(ts[0], ts[-1]), rng.uniform(1.5, 5.0) * 3600
        sub = [lr for lr, t in zip(items, ts) if t0 <= t <= t0 + w]
        if sub:
            items, scope = sub, f"janela {to_hms(t0)}-{to_hms(t0 + w)}"
    return items, scope


def apply_group(ctx, sch, rng, edit):
    """Aplica `edit(ctx, copias, rng)` às viagens do alvo, valida e repara os blocos dos veículos."""
    picked = pick_group(ctx, sch, rng)
    if picked is None:
        return None
    items, scope = picked
    copies = [copy.deepcopy(r) for _, r in items]
    msg = edit(ctx, copies, rng)
    if not msg or any(validate(ctx, c) for c in copies):
        return None
    if not bounds_ok(ctx, ctx.g.km(items[0][1].links), ctx.g.km(copies[0].links)):
        return None
    repl = {id(r): c for (_, r), c in zip(items, copies)}
    shifts = plan_shifts(ctx, sch, repl)
    if shifts is None:
        return None
    for l in sch.lines:
        l.routes = [repl.get(id(x), x) for x in l.routes]
    for _, r in sch.routes():
        for d in r.deps:
            if shifts.get(id(d)):
                d.time += shifts[id(d)]
    extra = f"; adiou {len(shifts)} viagens p/ manter blocos" if shifts else ""
    return f"{msg} [{len(copies)} viagens, {scope}]{extra}"


# ----------------------------------------------------------------------------
# Edições de percurso: todas as cópias `rs` têm as mesmas paradas e links (mesmo padrão),
# então a decisão é tomada em rs[0] e aplicada a todas (cada viagem mantém seus offsets).
# ----------------------------------------------------------------------------
def e_reroute(ctx, rs, rng):
    g, r = ctx.g, rs[0]
    pos = stop_positions(ctx, r)
    k = rng.randrange(len(pos) - 1)
    a, b = pos[k], pos[k + 1]
    if b - a < 2:
        return None
    old_mid = r.links[a + 1:b]
    old_tt = g.tt_path(r.links[a + 1:b + 1])
    factor = rng.uniform(3, 10)
    alt = g.path(g.link[r.links[a]][1], g.link[r.links[b]][0], penal=set(old_mid),
                 factor=factor, max_cost=old_tt * ctx.a.max_detour * factor)
    if alt is None or alt == old_mid:
        return None
    new_tt = g.tt_path(alt) + g.tt(r.links[b])
    if new_tt > ctx.a.max_detour * old_tt:
        return None
    delta = round((new_tt - old_tt) * ctx.time_factor)       # segundos inteiros
    ref0, ref1 = r.stops[k].ref, r.stops[k + 1].ref
    for x in rs:
        x.links[a + 1:b] = list(alt)
        for s in x.stops[k + 1:]:
            if s.arr is not None:
                s.arr += delta
            if s.dep is not None:
                s.dep += delta
        fix_offsets(x)
    return f"trecho {ref0}->{ref1}: {len(old_mid)}->{len(alt)} links ({delta:+.0f}s)"


def e_remove_stop(ctx, rs, rng):
    n = len(rs[0].stops)
    if n < 3:
        return None
    k = rng.randrange(1, n - 1)
    ref = rs[0].stops[k].ref
    for x in rs:
        x.stops.pop(k)
    return f"remove parada {ref}"


def e_insert_stop(ctx, rs, rng):
    r, g = rs[0], ctx.g
    pos, have = stop_positions(ctx, r), {s.ref for s in r.stops}
    cands = [(k, p, sid) for k in range(len(pos) - 1) for p in range(pos[k] + 1, pos[k + 1])
             for sid in ctx.by_link.get(r.links[p], ()) if sid not in have]
    if not cands:
        return None
    k, p, sid = rng.choice(cands)
    seg = sum(g.link[l][2] for l in r.links[pos[k] + 1:pos[k + 1] + 1]) or 1.0
    frac = sum(g.link[l][2] for l in r.links[pos[k] + 1:p + 1]) / seg
    for x in rs:
        s0, s1 = x.stops[k], x.stops[k + 1]
        t = round(t_out(s0) + (t_in(s1) - t_out(s0)) * frac)
        x.stops.insert(k + 1, RStop(sid, t, t, s0.await_dep))
    return f"insere parada {sid}"


def e_truncate(ctx, rs, rng):
    r = rs[0]
    if len(r.stops) < 4:
        return None
    pos = stop_positions(ctx, r)
    m = rng.randint(1, min(3, len(r.stops) - 3))
    cut = pos[-m - 1] + 1
    for x in rs:
        x.links = x.links[:cut]
        del x.stops[-m:]
    return f"trunca {m} parada(s) finais"


def e_extend(ctx, rs, rng):
    g, r = ctx.g, rs[0]
    lx, ly = ctx.stop_xy[r.stops[-1].ref]
    have = {s.ref for s in r.stops}
    cands = []
    for sid, (x, y) in ctx.stop_xy.items():
        d = math.hypot(x - lx, y - ly)
        if sid not in have and ctx.a.ext_min_m <= d <= ctx.a.ext_max_m:
            cands.append((sid, d))
    rng.shuffle(cands)
    for sid, d in cands[:15]:
        tl = ctx.stop_link[sid]
        if tl in r.links:
            continue
        path = g.path(g.link[r.links[-1]][1], g.link[tl][0], max_cost=1.6 * d / 5.0)
        if path is None:
            continue
        add = path + [tl]
        tt = round(g.tt_path(add) * ctx.time_factor)
        for x in rs:
            last = x.stops[-1]
            had_dep = last.dep is not None
            if not had_dep:
                last.dep = last.arr
            t = t_out(last) + tt
            x.links = x.links + add
            x.stops.append(RStop(sid, t, t if had_dep else None, last.await_dep))
        return f"estende ate {sid} (+{g.km(add):.1f} km)"
    return None


# ----------------------------------------------------------------------------
# Mutações de horário / frequência (a frota é fixa: nunca criam veículos)
# ----------------------------------------------------------------------------
def m_shift_block(ctx, sch, rng):
    """Desloca no tempo todas as viagens de um veículo (mantém o bloco do veículo coerente)."""
    deps_by_veh = defaultdict(list)
    for _, r in sch.routes():
        for d in r.deps:
            deps_by_veh[d.veh].append(d)
    v = rng.choice(sorted(deps_by_veh))
    delta = rng.choice([-1, 1]) * rng.randint(1, ctx.a.max_shift_min) * 60
    if min(d.time for d in deps_by_veh[v]) + delta < 0:
        return None
    for d in deps_by_veh[v]:
        d.time += delta
    return f"desloca bloco do veículo {v} em {delta / 60:+.0f} min ({len(deps_by_veh[v])} viagens)"


def m_reduce(ctx, sch, rng):
    """Remove 1 em cada k viagens de um padrão (aumenta o intervalo entre partidas)."""
    picked = pick_group(ctx, sch, rng)
    if picked is None:
        return None
    items, scope = picked
    k = rng.choice([2, 3])
    rem = items[k - 1::k]
    n_rem = sum(len(r.deps) for _, r in rem)
    if len(items) < 4 or len(rem) < 2 or ctx.n_deps(sch) - n_rem < ctx.min_trips:
        return None
    ids = {id(r) for _, r in rem}
    for l in sch.lines:
        l.routes = [x for x in l.routes if id(x) not in ids]
    sch.lines = [l for l in sch.lines if l.routes]
    return f"remove 1 em cada {k} viagens ({len(rem)} removidas, {scope})"


def m_increase(ctx, sch, rng):
    """Insere viagens nos maiores intervalos de um padrão, usando veículos ociosos da mesma linha."""
    picked = pick_group(ctx, sch, rng)
    if picked is None:
        return None
    items, scope = picked
    cand = [(first_dep(items[i + 1][1]) - first_dep(items[i][1]), i) for i in range(len(items) - 1)]
    cand = [c for c in cand if c[0] >= 2 * ctx.a.min_headway_s]
    if not cand:
        return None
    rng.shuffle(cand)
    want = rng.randint(1, min(8, len(cand)))
    pool = sorted(ctx.line_vehicles.get(items[0][0].id, ()))
    busy = {v: [(s, e) for s, e, _, _ in xs]
            for v, xs in vehicle_trips(sch, set(pool)).items()}
    lay, added = ctx.a.layover_s, []
    for _, i in cand:
        if len(added) >= want or ctx.n_deps(sch) + len(added) >= ctx.max_trips:
            break
        line, tmpl = items[i]
        t_new = round((first_dep(tmpl) + first_dep(items[i + 1][1])) / 2)
        s_new, e_new = t_new + t_out(tmpl.stops[0]), t_new + t_in(tmpl.stops[-1])
        free = [v for v in pool
                if all(e_new + lay <= s or e + lay <= s_new for s, e in busy.get(v, ()))]
        if not free:
            continue
        v = rng.choice(free)
        new = copy.deepcopy(tmpl)
        new.id = f"{tmpl.id}_f{ctx.new_id()}"
        new.deps = [Dep(f"{new.id}_{to_hms(t_new).replace(':', '-')}", t_new, v)]
        added.append((line, new))
        busy.setdefault(v, []).append((s_new, e_new))
    if not added:
        return None
    for line, new in added:
        line.routes.append(new)
    return f"adiciona {len(added)} viagem(ns) em intervalos longos ({scope}); frota fixa"


GEOMETRY = {"reroute_trecho": (e_reroute, 3), "insere_parada": (e_insert_stop, 2),
            "remove_parada": (e_remove_stop, 2), "trunca_rota": (e_truncate, 1),
            "estende_rota": (e_extend, 1)}
TIMETABLE = {"desloca_bloco": (m_shift_block, 2), "reduz_frequencia": (m_reduce, 1.5),
             "aumenta_frequencia": (m_increase, 1.5)}


def mutate(ctx, base, rng, n_mut):
    """Clona a rede base e aplica n_mut mutações válidas. Retorna (rede, log)."""
    sch, log = base.clone(), []
    table = {**GEOMETRY, **TIMETABLE}
    names, weights = list(table), [w for _, w in table.values()]
    for _ in range(n_mut * 40):
        if len(log) >= n_mut:
            break
        name = rng.choices(names, weights)[0]
        fn = table[name][0]
        msg = apply_group(ctx, sch, rng, fn) if name in GEOMETRY else fn(ctx, sch, rng)
        if msg:
            log.append({"mutacao": name, "detalhe": msg})
    return sch, log


# ----------------------------------------------------------------------------
# Programa principal
# ----------------------------------------------------------------------------
def run(a):
    t0 = time.time()
    g = RoadGraph.load(a.network, a.modes.split(","))
    print(f"rede viária: {len(g.link)} links, {sum(len(v) for v in g.out.values())} "
          f"usáveis no roteamento ({time.time() - t0:.0f}s)")
    base, vtype = load_schedule(a.schedule), load_vehicle_types(a.vehicles)
    ctx = Ctx(g, base, a)
    missing = {d.veh for _, r in base.routes() for d in r.deps} - set(vtype)
    if missing:
        raise SystemExit(f"veículos do schedule ausentes em {a.vehicles}: {sorted(missing)[:5]}")
    s0 = stats(ctx, base)
    print(f"rede inicial: {s0} | tempo programado / fluxo livre = {ctx.time_factor:.2f}")
    if ctx.frozen:
        print(f"AVISO: {len(ctx.frozen)} rota(s) da base não passam na validação e ficam sem mutar")
    if ctx.bad_vehicles:
        print(f"AVISO: {len(ctx.bad_vehicles)} veículo(s) já têm viagens sobrepostas na base "
              f"({sorted(ctx.bad_vehicles)[:5]}); se uma mutação os afetar, o reparo de blocos os corrige")
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    resumo = {"parametros": {k: v for k, v in vars(a).items()}, "base": s0, "variantes": []}
    for i in range(1, a.n + 1):
        rng = random.Random(a.seed * 1000 + i)
        sch, log = mutate(ctx, base, rng, rng.randint(a.min_mut, a.max_mut))
        d = out / f"variante_{i}"
        d.mkdir(exist_ok=True)
        write_schedule(sch, d / "schedule.xml")
        shutil.copyfile(a.vehicles, d / "vehicles.xml")           # frota fixa: o mesmo arquivo
        (d / "cromossomo.json").write_text(json.dumps(to_dict(sch), ensure_ascii=False), encoding="utf-8")
        info = {"variante": i, "n_mutacoes": len(log), "diff_base": diff(base, sch),
                "stats": stats(ctx, sch), "mutacoes": log}
        resumo["variantes"].append(info)
        print(f"variante_{i}: {len(log)} mutações | {info['diff_base']} | {info['stats']}")
    (out / "resumo.json").write_text(json.dumps(resumo, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"pronto em {time.time() - t0:.0f}s -> {out}/")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--network", required=True, help="network.xml[.gz] (rede viária)")
    p.add_argument("--schedule", required=True, help="schedule.xml inicial (transitSchedule)")
    p.add_argument("--vehicles", required=True, help="vehicles.xml da frota (mantido fixo)")
    p.add_argument("--out", default="variantes", help="pasta de saída")
    p.add_argument("--n", type=int, default=5, help="número de variantes (default 5)")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--min-mut", type=int, default=3, help="mínimo de mutações por variante")
    p.add_argument("--max-mut", type=int, default=8, help="máximo de mutações por variante")
    p.add_argument("--modes", default="car,bus", help="modos de link usáveis no roteamento")
    p.add_argument("--max-detour", type=float, default=1.5, help="desvio máx. ao reroutear um trecho (x tempo original)")
    p.add_argument("--min-km", type=float, default=None, help="comprimento mínimo de rota (km)")
    p.add_argument("--max-km", type=float, default=None, help="comprimento máximo de rota (km)")
    p.add_argument("--min-trips", type=int, default=None, help="mín. de viagens (default 85%% da base)")
    p.add_argument("--max-trips", type=int, default=None, help="máx. de viagens (default 115%% da base)")
    p.add_argument("--min-headway-s", type=float, default=120, help="intervalo mínimo entre viagens ao aumentar frequência")
    p.add_argument("--max-shift-min", type=int, default=15, help="deslocamento máx. do bloco de um veículo")
    p.add_argument("--layover-s", type=float, default=0, help="folga mínima entre viagens consecutivas de um veículo")
    p.add_argument("--max-cascade-min", type=float, default=20, help="adiamento máx. ao reparar blocos")
    p.add_argument("--ext-min-m", type=float, default=300, help="distância mín. para prolongar rota")
    p.add_argument("--ext-max-m", type=float, default=3000, help="distância máx. para prolongar rota")
    run(p.parse_args())


if __name__ == "__main__":
    main()