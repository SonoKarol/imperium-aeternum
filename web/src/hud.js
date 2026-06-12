// DOM-based HUD: bars, messages, boss bar, level-up panel, lock-on reticle.
export class HUD {
  constructor() {
    this.el = {
      hud: document.getElementById('hud'),
      hpFill: document.querySelector('#hpbar .fill'),
      hpGhost: document.querySelector('#hpbar .ghost'),
      hpBar: document.getElementById('hpbar'),
      stFill: document.querySelector('#stbar .fill'),
      stBar: document.getElementById('stbar'),
      flasks: document.getElementById('flasks'),
      gloria: document.getElementById('gloriaNum'),
      prompt: document.getElementById('prompt'),
      bossbar: document.getElementById('bossbar'),
      bossname: document.getElementById('bossname'),
      bossFill: document.querySelector('#bosshp .fill'),
      msg: document.getElementById('msg'),
      submsg: document.getElementById('submsg'),
      vignette: document.getElementById('vignette'),
      lockon: document.getElementById('lockon'),
      levelup: document.getElementById('levelup'),
      lvlCost: document.getElementById('lvlCost'),
      statVig: document.getElementById('statVig'),
      statEnd: document.getElementById('statEnd'),
      statStr: document.getElementById('statStr'),
      title: document.getElementById('title'),
      paused: document.getElementById('paused'),
    };
    this.msgTimer = null;
    this.lastGloria = -1;
  }

  show() { this.el.hud.style.display = 'block'; }

  setVitals(hp, maxHp, st, maxSt) {
    this.el.hpBar.style.width = `${Math.min(420, 140 + maxHp)}px`;
    this.el.stBar.style.width = `${Math.min(360, 110 + maxSt)}px`;
    const fr = Math.max(0, hp / maxHp);
    this.el.hpFill.style.transform = `scaleX(${fr})`;
    this.el.hpGhost.style.transform = `scaleX(${fr})`;
    this.el.stFill.style.transform = `scaleX(${Math.max(0, st / maxSt)})`;
    this.el.vignette.classList.toggle('low', fr > 0 && fr < 0.3);
  }

  setFlasks(n, max) {
    let html = '';
    for (let i = 0; i < max; i++) html += `<span class="${i < n ? '' : 'empty'}">🏺</span> `;
    this.el.flasks.innerHTML = html;
  }

  setGloria(n) {
    if (n === this.lastGloria) return;
    this.lastGloria = n;
    this.el.gloria.textContent = n;
  }

  setPrompt(text) {
    if (text) {
      this.el.prompt.innerHTML = text;
      this.el.prompt.style.opacity = 1;
    } else {
      this.el.prompt.style.opacity = 0;
    }
  }

  hurtFlash() {
    this.el.vignette.classList.add('hurt');
    setTimeout(() => this.el.vignette.classList.remove('hurt'), 120);
  }

  message(text, color = '#d8d0c0', dur = 2600, sub = '') {
    clearTimeout(this.msgTimer);
    this.el.msg.textContent = text;
    this.el.msg.style.color = color;
    this.el.msg.style.opacity = 1;
    this.el.submsg.textContent = sub;
    this.el.submsg.style.opacity = sub ? 1 : 0;
    this.msgTimer = setTimeout(() => {
      this.el.msg.style.opacity = 0;
      this.el.submsg.style.opacity = 0;
    }, dur);
  }

  boss(name, frac) {
    if (name === null) { this.el.bossbar.style.display = 'none'; return; }
    this.el.bossbar.style.display = 'block';
    this.el.bossname.textContent = name;
    this.el.bossFill.style.transform = `scaleX(${Math.max(0, frac)})`;
  }

  // Lock-on reticle positioned via projected screen coords (null hides it).
  lockonAt(screen) {
    if (!screen) { this.el.lockon.style.display = 'none'; return; }
    this.el.lockon.style.display = 'block';
    this.el.lockon.style.left = `${screen.x}px`;
    this.el.lockon.style.top = `${screen.y}px`;
  }

  levelPanel(show, stats = null, cost = 0, canAfford = false) {
    this.el.levelup.style.display = show ? 'block' : 'none';
    if (show && stats) {
      this.el.lvlCost.textContent = cost;
      this.el.statVig.textContent = stats.vigor;
      this.el.statEnd.textContent = stats.endurance;
      this.el.statStr.textContent = stats.strength;
      for (const id of ['rowVig', 'rowEnd', 'rowStr']) {
        document.getElementById(id).classList.toggle('nope', !canAfford);
      }
    }
  }

  hideTitle() { this.el.title.style.display = 'none'; }
  showPaused(on) { this.el.paused.style.display = on ? 'flex' : 'none'; }
}
