// Lightweight particle bursts (blood, sparks, dust) and the boss shockwave ring.
import * as THREE from 'three';

export class FX {
  constructor(scene) {
    this.scene = scene;
    this.bursts = [];
    this.rings = [];
  }

  burst(pos, { color = 0xa01818, count = 14, speed = 4, size = 0.08, life = 0.5, up = 2.5 } = {}) {
    const geo = new THREE.BufferGeometry();
    const positions = new Float32Array(count * 3);
    const vels = [];
    for (let i = 0; i < count; i++) {
      positions[i * 3] = pos.x;
      positions[i * 3 + 1] = pos.y;
      positions[i * 3 + 2] = pos.z;
      const a = Math.random() * Math.PI * 2;
      const v = speed * (0.4 + Math.random() * 0.6);
      vels.push(new THREE.Vector3(Math.cos(a) * v, up * (0.3 + Math.random() * 0.7), Math.sin(a) * v));
    }
    geo.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    const mat = new THREE.PointsMaterial({ color, size, transparent: true, opacity: 1, depthWrite: false });
    const points = new THREE.Points(geo, mat);
    this.scene.add(points);
    this.bursts.push({ points, vels, t: 0, life });
  }

  blood(pos)  { this.burst(pos, { color: 0x8b1212, count: 16, speed: 3.5, life: 0.55 }); }
  spark(pos)  { this.burst(pos, { color: 0xffd060, count: 10, speed: 5, size: 0.06, life: 0.3, up: 1.5 }); }
  dust(pos)   { this.burst(pos, { color: 0x9a8a6c, count: 20, speed: 5, size: 0.12, life: 0.7, up: 1.2 }); }
  soul(pos)   { this.burst(pos, { color: 0xc9a227, count: 24, speed: 1.5, size: 0.1, life: 1.1, up: 4 }); }

  // Expanding damage ring for the boss slam.
  shockwave(pos, maxR, dur) {
    const geo = new THREE.RingGeometry(0.8, 1.0, 40);
    geo.rotateX(-Math.PI / 2);
    const mat = new THREE.MeshBasicMaterial({ color: 0xffaa44, transparent: true, opacity: 0.9, side: THREE.DoubleSide, depthWrite: false });
    const ring = new THREE.Mesh(geo, mat);
    ring.position.set(pos.x, 0.15, pos.z);
    this.scene.add(ring);
    this.rings.push({ ring, t: 0, dur, maxR });
  }

  update(dt) {
    for (let i = this.bursts.length - 1; i >= 0; i--) {
      const b = this.bursts[i];
      b.t += dt;
      const pos = b.points.geometry.attributes.position.array;
      for (let j = 0; j < b.vels.length; j++) {
        const v = b.vels[j];
        v.y -= 9 * dt;
        pos[j * 3] += v.x * dt;
        pos[j * 3 + 1] = Math.max(0.03, pos[j * 3 + 1] + v.y * dt);
        pos[j * 3 + 2] += v.z * dt;
      }
      b.points.geometry.attributes.position.needsUpdate = true;
      b.points.material.opacity = 1 - b.t / b.life;
      if (b.t >= b.life) {
        this.scene.remove(b.points);
        b.points.geometry.dispose();
        b.points.material.dispose();
        this.bursts.splice(i, 1);
      }
    }
    for (let i = this.rings.length - 1; i >= 0; i--) {
      const r = this.rings[i];
      r.t += dt;
      const k = Math.min(1, r.t / r.dur);
      const s = 1 + k * r.maxR;
      r.ring.scale.set(s, 1, s);
      r.ring.material.opacity = 0.9 * (1 - k);
      if (k >= 1) {
        this.scene.remove(r.ring);
        r.ring.geometry.dispose();
        r.ring.material.dispose();
        this.rings.splice(i, 1);
      }
    }
  }
}
