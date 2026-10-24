// QoE Probe instrumentation. Injected at document start into every frame.
// Hooks Media Source Extensions (codec strings, bytes appended per track) and
// HTML5 <video> events/state, and posts JSON to the native bridge window.QoE.
(function () {
  if (window.__qoeInstalled) return;
  window.__qoeInstalled = true;

  var st = { vc: '', ac: '', vb: 0, ab: 0 };
  var isTop = (window === window.top);
  var idBase = Math.floor(Math.random() * 1e6) * 1000;
  var ids = 0;

  function send(o) { try { if (window.QoE) window.QoE.post(JSON.stringify(o)); } catch (e) {} }
  function codecOf(m) {
    var r = /codecs\s*=\s*"?([^";]+)/i.exec(m || '');
    return r ? r[1].trim() : String(m || '');
  }
  function tag(sb, mime) {
    var v = /^\s*video/i.test(mime);
    sb.__qk = v ? 'v' : 'a';
    var c = codecOf(mime);
    if (v) st.vc = c; else st.ac = c;
    send({ t: 'codec', k: sb.__qk, mime: String(mime) });
  }

  // ---- MSE hooks: codec + media bytes (ABR bitrate) ----
  try {
    [window.MediaSource, window.ManagedMediaSource, window.WebKitMediaSource].forEach(function (MS) {
      if (!MS || !MS.prototype || !MS.prototype.addSourceBuffer) return;
      if (Object.prototype.hasOwnProperty.call(MS.prototype, '__qoe')) return;
      MS.prototype.__qoe = true;
      var orig = MS.prototype.addSourceBuffer;
      MS.prototype.addSourceBuffer = function (mime) {
        var sb = orig.apply(this, arguments);
        try { tag(sb, mime); } catch (e) {}
        return sb;
      };
    });
    if (window.SourceBuffer && !SourceBuffer.prototype.__qoe) {
      SourceBuffer.prototype.__qoe = true;
      var oa = SourceBuffer.prototype.appendBuffer;
      SourceBuffer.prototype.appendBuffer = function (buf) {
        try {
          var n = buf ? (buf.byteLength || 0) : 0;
          if (this.__qk === 'a') st.ab += n; else st.vb += n;
        } catch (e) {}
        return oa.apply(this, arguments);
      };
      var oc = SourceBuffer.prototype.changeType;
      if (oc) SourceBuffer.prototype.changeType = function (mime) {
        try { tag(this, mime); } catch (e) {}
        return oc.apply(this, arguments);
      };
    }
  } catch (e) {}

  function vid(v) { if (!v.__qid) v.__qid = idBase + (++ids); return v.__qid; }

  // ---- Media events (captured at document level; media events don't bubble) ----
  ['loadstart', 'loadedmetadata', 'canplay', 'playing', 'waiting', 'stalled', 'pause',
   'seeking', 'seeked', 'ended', 'error', 'resize', 'ratechange', 'emptied'].forEach(function (ev) {
    document.addEventListener(ev, function (e) {
      var v = e.target;
      if (!v || v.tagName !== 'VIDEO') return;
      send({ t: 'ev', ev: ev, id: vid(v), w: v.videoWidth, h: v.videoHeight,
             ct: v.currentTime, err: v.error ? v.error.code : 0 });
    }, true);
  });

  // Pick the video the user is watching: playing > has data > largest on screen.
  function pick() {
    var best = null, bs = 0, vs = document.getElementsByTagName('video');
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i], r = v.getBoundingClientRect();
      var a = Math.max(0, r.width) * Math.max(0, r.height);
      var s = (a + 1) * (v.paused ? 1 : 1000) * (v.readyState > 0 ? 10 : 1);
      if (s > bs) { bs = s; best = v; }
    }
    return best;
  }

  setInterval(function () {
    var v = pick();
    if (!v) {
      if (isTop) send({ t: 's', nov: 1, vb: st.vb, ab: st.ab, vc: st.vc, ac: st.ac });
      return;
    }
    var ct = v.currentTime, ahead = 0;
    try {
      for (var i = 0; i < v.buffered.length; i++) {
        if (v.buffered.start(i) <= ct + 0.25 && v.buffered.end(i) >= ct) ahead = v.buffered.end(i) - ct;
      }
    } catch (e) {}
    var df = 0, tf = 0;
    try { var q = v.getVideoPlaybackQuality(); df = q.droppedVideoFrames; tf = q.totalVideoFrames; } catch (e) {}
    var ql = '';
    try { var p = document.getElementById('movie_player'); if (p && p.getPlaybackQuality) ql = p.getPlaybackQuality(); } catch (e) {}
    var src = v.currentSrc || '';
    send({
      t: 's', id: vid(v), ct: ct, du: isFinite(v.duration) ? v.duration : -1,
      p: v.paused, e: v.ended, sk: v.seeking, rs: v.readyState,
      w: v.videoWidth, h: v.videoHeight, r: v.playbackRate, ba: ahead,
      df: df, tf: tf, ql: ql,
      dl: src.indexOf('blob:') === 0 ? 'mse' : (src ? 'progressive' : ''),
      vb: st.vb, ab: st.ab, vc: st.vc, ac: st.ac
    });
  }, 500);
})();
