// Spoken guidance with the browser's speech synthesis (Spanish): route manoeuvres and traffic ahead.
const ORD = ['', 'primera', 'segunda', 'tercera', 'cuarta', 'quinta', 'sexta'];

function dist(m) {
  if (m >= 950) {
    const km = Math.round(m / 100) / 10;
    return (Number.isInteger(km) ? km : String(km).replace('.', ',')) + (km === 1 ? ' kilómetro' : ' kilómetros');
  }
  return Math.max(50, Math.round(m / 50) * 50) + ' metros';
}

function action(st) {
  const por = st.text ? ' por ' + st.text : '';
  switch (st.kind) {
    case 'L': return 'gira a la izquierda' + por;
    case 'R': return 'gira a la derecha' + por;
    case 'SL': return 'gira ligeramente a la izquierda' + por;
    case 'SR': return 'gira ligeramente a la derecha' + por;
    case 'S': return 'sigue recto' + por;
    case 'U': return 'haz un cambio de sentido';
    case 'X': return 'toma la salida' + (st.exitNo > 0 ? ' número ' + st.exitNo : '') + (st.text ? ' hacia ' + st.text : '');
    case 'RB': return st.exitNo >= 1 && st.exitNo <= 6 ? 'en la rotonda, toma la ' + ORD[st.exitNo] + ' salida' + por
      : st.exitNo > 0 ? 'en la rotonda, toma la salida número ' + st.exitNo + por : 'en la rotonda, toma la salida indicada' + por;
    default: return 'continúa' + por;
  }
}

export class VoiceGuide {
  constructor() {
    this.enabled = true; this.said = []; this.stage = new Map(); this.keys = new Set(); this.last = 0; this.trafficKey = '';
    this.ok = 'speechSynthesis' in window;
    this.voice = null;
    if (this.ok) {
      const pick = () => { this.voice = speechSynthesis.getVoices().find((v) => /^es(-|_)ES/i.test(v.lang)) || speechSynthesis.getVoices().find((v) => /^es/i.test(v.lang)) || null; };
      pick(); speechSynthesis.addEventListener?.('voiceschanged', pick);
    }
  }
  /** Phones only allow speech after a tap: call this from a user gesture. */
  unlock() { if (this.ok) { const u = new SpeechSynthesisUtterance(' '); u.volume = 0; speechSynthesis.speak(u); } }
  reset() { this.stage.clear(); this.keys.clear(); this.trafficKey = ''; }
  say(text, urgent = false) {
    this.said.push(text);
    if (this.said.length > 50) this.said.shift();
    if (!this.ok || !this.enabled) return;
    if (urgent) speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(text); u.lang = 'es-ES'; if (this.voice) u.voice = this.voice;
    speechSynthesis.speak(u);
  }

  /** ctx: { route, sN, highway, now, traffic: {level, dist, delayMin} | null, alt: {saveMin} | null } */
  update(ctx) {
    if (!this.enabled || ctx.now - this.last < 250) return;
    this.last = ctx.now;
    const { route, sN, highway } = ctx;
    const nx = route ? route.next(sN) : null;
    if (nx) {
      const st = nx.step, d = nx.dist, id = Math.round(st.offset);
      const far = highway ? 1000 : 400, near = highway ? 400 : 150, done = this.stage.get(id) || 0;
      if (st.kind === 'ARRIVE') {
        if (d <= 70 && done < 2) { this.stage.set(id, 2); this.say('Has llegado a tu destino'); }
        else if (d <= far && d > 150 && done < 1) { this.stage.set(id, 1); this.say('Tu destino está a ' + dist(d)); }
      } else if (d <= near && done < 2) {
        this.stage.set(id, 2); const a = action(st); this.say(a.charAt(0).toUpperCase() + a.slice(1));
      } else if (d <= far && d > near * 1.5 && done < 1) {
        this.stage.set(id, 1); this.say('En ' + dist(d) + ', ' + action(st));
      }
    }
    const tr = ctx.traffic;
    if (route && tr && tr.level > 0 && tr.dist <= 3000) {
      const key = 't' + Math.round((sN + tr.dist) / 200) + '/' + tr.level;
      if (key !== this.trafficKey) {
        this.trafficKey = key;
        const what = tr.level === 1 ? 'Algo de tráfico' : tr.level === 2 ? 'Tráfico denso' : 'Atención, caravana o corte de tráfico';
        const where = tr.dist <= 30 ? ' en tu ruta' : ' dentro de ' + dist(tr.dist);
        const delay = tr.delayMin > 0 ? ', ' + tr.delayMin + (tr.delayMin === 1 ? ' minuto' : ' minutos') + ' de retraso' : '';
        this.say(what + where + delay);
        const alt = ctx.alt;
        if (alt && alt.saveMin >= 2 && !this.keys.has('alt' + alt.saveMin)) { this.keys.add('alt' + alt.saveMin); this.say('Hay una ruta alternativa que ahorra ' + alt.saveMin + ' minutos. Puedes elegirla en la pantalla.'); }
      }
    } else if (!tr || tr.level === 0) this.trafficKey = '';
  }
}
