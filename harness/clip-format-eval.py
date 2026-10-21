#!/usr/bin/env python3
"""Menu clip format evaluation (docs/findings-clip-format-2026-10-20.md): the same 4 s / 24 fps / 512-wide window of a few
menu clips encoded as GIF (harness/menu-gifs.py's chain), animated WebP, APNG, animated JPEG XL (cjxl) and animated AVIF
(libaom via ffmpeg), SDR and HDR (PQ / BT.2020), with size, quality against a lossless reference, and the pure-Java decode
time of each file (tools/ClipDecodeBench.java: jxlatte, javif, TwelveMonkeys WebP, the game's GIF path) plus a bit-exactness
check of the Java decoders against libjxl / dav1d. Run it as a queue media job (it encodes):

  harness/queue.sh submit media --label clip-eval -- python3 harness/clip-format-eval.py <out dir> [clip...]

Needs: ffmpeg (libaom, libdav1d, libjxl, libwebp, libvmaf), cjxl, the game JRE, and CLIPBENCH_CP (classpath of the bench:
jxlatte + javif + TwelveMonkeys classes, pzopt build/classes, the game jar, the compiled bench).

Quality: SDR files are scored against the SDR reference (the GIF chain's tone map, crop and Lanczos scale without its
denoise, RGB 8-bit) with VMAF (vmaf_v0.6.1), SSIM and PSNR on yuv444p. HDR files are scored against the HDR reference
(the recording's PQ / BT.2020 cropped and scaled, RGB 16-bit) with PSNR / SSIM on the PQ code values (yuv444p12), and
also as an SDR viewer would see them: decoded, tone-mapped with the GIF chain's operator and scored like an SDR file.
"""
import importlib.util
import json
import os
import shutil
import statistics
import subprocess
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location('menu_gifs', os.path.join(REPO, 'harness/menu-gifs.py'))
mg = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mg)
JAVA = os.environ.get('PZ_JAVA', '/games/steamapps/common/ProjectZomboid/projectzomboid/jre64/bin/java')
CP = os.environ.get('CLIPBENCH_CP', '')

# name, run, kind, after, crop, denoise (the GIF's), hdr source
EVAL = {
    'towndrive': dict(run='td-rel-new-1', align='route', after=12.0, crop=None, denoise=True),
    'hdrday': dict(run='enh-hdr-day', align='route', after=3.0, crop='2900:80:1280:540', denoise=False),
    'hdr': dict(run='enh-hdr-night', align='route', after=6.0, crop='1360:760:2560:1080', denoise=True),
    'visuals': dict(run='ge-clear-on', kind='pane', after=2.0, crop=mg.PANE_CROP, denoise=True),
}
W, FPS, DUR = mg.W, mg.FPS, mg.DUR
TONEMAP_RGB = mg.TONEMAP.replace(',format=yuv420p', '') + ',format=rgb24'


def sh(cmd, **kw):
    print('+', ' '.join(str(c) for c in cmd)[:400], file=sys.stderr, flush=True)
    return subprocess.run([str(c) for c in cmd], check=True, **kw)


def geometry(crop):
    if crop:
        cx, cy, cw, ch = (int(v) for v in crop.split(':'))
        return f'crop={cw}:{ch}:{cx}:{cy},', max(2, round(ch * W / cw / 2) * 2)
    return '', mg.H


def raw_in(path, pix, w, h):
    return ['-f', 'rawvideo', '-pix_fmt', pix, '-s', f'{w}x{h}', '-r', str(FPS), '-i', path]


def decode_raw(src, out, pix, extra=''):
    """Any encoded file -> raw frames (passthrough timing: one raw frame per coded frame). An AVIF sequence holds a still
    item (stream 0, the first frame) and the sequence track: take the stream with the most frames."""
    vf = (extra + ',' if extra else '') + f'format={pix}'
    m = []
    if src.endswith('.avif'):
        r = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'stream=index,nb_frames', '-of', 'csv=p=0', src],
                           capture_output=True, text=True).stdout.split()
        best = max((int(f or 0), int(i)) for i, f in (l.split(',') for l in r))
        m = ['-map', f'0:{best[1]}']
    sh(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-i', src] + m + ['-fps_mode', 'passthrough', '-vf', vf, '-f', 'rawvideo', out])


def avif_encode(raw, w, h, out, hdr, chroma='444', q=60, speed=6):
    """Raw RGB frames -> y4m (full range, BT.709 / BT.2020 matrix) -> avifenc (libaom) animated AVIF, 24 fps."""
    y4m = out + '.y4m'
    pix = 'rgb48le' if hdr else 'rgb24'
    fmt = f'yuv{chroma}p10le' if hdr else f'yuv{chroma}p'
    mat = 'bt2020' if hdr else 'bt709'
    sh(['ffmpeg', '-hide_banner', '-v', 'error', '-y'] + raw_in(raw, pix, w, h) +
       ['-vf', f'scale=out_color_matrix={mat}:out_range=pc,format={fmt}', '-color_range', 'pc', '-strict', '-1', y4m])
    cicp = '9/16/9' if hdr else '1/13/1'
    sh(['avifenc', '--cicp', cicp, '-r', 'full', '-d', '10' if hdr else '8', '-y', chroma, '--fps', str(FPS), '-q', str(q),
        '-s', str(speed), '-j', 'all', y4m, out], stdout=subprocess.DEVNULL)
    os.unlink(y4m)


def frames_of(path, w, h, bpp):
    return os.path.getsize(path) // (w * h * bpp)


def score_sdr(dist, ref, w, h):
    """VMAF / SSIM / PSNR of rgb24 raw frames against the rgb24 reference."""
    n = min(frames_of(dist, w, h, 3), frames_of(ref, w, h, 3))
    log = dist + '.vmaf.json'
    r = subprocess.run(['ffmpeg', '-hide_banner', '-y'] + raw_in(dist, 'rgb24', w, h) + raw_in(ref, 'rgb24', w, h) +
                       ['-frames:v', str(n), '-lavfi',
                        '[0]format=yuv444p,split=3[a1][a2][a3];[1]format=yuv444p,split=3[b1][b2][b3];'
                        f'[a1][b1]libvmaf=log_fmt=json:log_path={log}:n_threads=8;[a2][b2]ssim;[a3][b3]psnr',
                        '-f', 'null', '-'], capture_output=True, text=True)
    vm = json.load(open(log))['pooled_metrics']['vmaf']
    out = dict(vmaf=round(vm['mean'], 2), vmaf_min=round(vm['min'], 2), frames=n)
    for l in r.stderr.splitlines():
        if 'SSIM' in l and 'All:' in l:
            out['ssim'] = float(l.split('All:')[1].split()[0])
        if 'PSNR' in l and 'average:' in l:
            out['psnr'] = float(l.split('average:')[1].split()[0])
    return out


def score_hdr(dist, ref, w, h):
    """PSNR / SSIM on PQ code values (rgb48 -> yuv444p12, same matrix for both)."""
    n = min(frames_of(dist, w, h, 6), frames_of(ref, w, h, 6))
    r = subprocess.run(['ffmpeg', '-hide_banner', '-y'] + raw_in(dist, 'rgb48le', w, h) + raw_in(ref, 'rgb48le', w, h) +
                       ['-frames:v', str(n), '-lavfi',
                        '[0]format=yuv444p12le,split[a1][a2];[1]format=yuv444p12le,split[b1][b2];[a1][b1]ssim;[a2][b2]psnr',
                        '-f', 'null', '-'], capture_output=True, text=True)
    out = dict(frames=n)
    for l in r.stderr.splitlines():
        if 'SSIM' in l and 'All:' in l:
            out['pq_ssim'] = float(l.split('All:')[1].split()[0])
        if 'PSNR' in l and 'average:' in l:
            out['pq_psnr'] = float(l.split('average:')[1].split()[0])
    return out


def tonemap_raw(hdr_raw, out, w, h):
    """PQ rgb48 frames -> the GIF chain's tone map -> rgb24 (what an SDR screen shows of an HDR file)."""
    sh(['ffmpeg', '-hide_banner', '-v', 'error', '-y'] + raw_in(hdr_raw, 'rgb48le', w, h) +
       ['-vf', 'setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=gbr,format=gbrp16le,'
        'zscale=tin=smpte2084:pin=bt2020:min=gbr:t=linear:npl=200,format=gbrpf32le,tonemap=hable,'
        'zscale=p=bt709:t=bt709:m=bt709,format=rgb24', '-f', 'rawvideo', out])


def java_bench(path, dump=None, iterations=5):
    if not CP:
        return {}
    cmd = [JAVA, '-cp', CP, 'ClipDecodeBench', path, str(iterations)] + ([dump] if dump else [])
    r = subprocess.run(cmd, capture_output=True, text=True)
    line = (r.stdout.strip().splitlines() or [''])[-1]
    if r.returncode != 0 or not line.startswith('format='):
        return dict(java_error=(r.stderr.strip().splitlines() or ['?'])[-1][:200])
    kv = dict(x.split('=', 1) for x in line.split())
    return dict(java_frames=int(kv['frames']), java_cold_ms=float(kv['cold_ms']), java_warm_ms=float(kv['warm_ms']),
                java_ms_per_frame=float(kv['ms_per_frame']))


def exact(java_raw, ref_raw, w, h):
    """Max abs difference (16-bit units) and PSNR between the Java decode and the reference decoder's."""
    import numpy as np
    a = np.fromfile(java_raw, dtype='<u2')
    b = np.fromfile(ref_raw, dtype='<u2')
    n = min(a.size, b.size)
    if n == 0:
        return dict(java_vs_ref='no frames')
    d = np.abs(a[:n].astype(np.int32) - b[:n].astype(np.int32))
    mse = float((d.astype(np.float64) ** 2).mean())
    psnr = 99.0 if mse == 0 else 10 * np.log10(65535.0 ** 2 / mse)
    return dict(java_max_diff=int(d.max()), java_psnr=round(psnr, 1), java_frames_cmp=n // (w * h * 3),
                java_ref_frames=b.size // (w * h * 3))


def run(out_dir, names):
    os.makedirs(out_dir, exist_ok=True)
    results = {}
    for name in names:
        c = EVAL[name]
        work = os.path.join(out_dir, name)
        os.makedirs(work, exist_ok=True)
        pane = c.get('kind') == 'pane'
        if pane:
            d = mg.st.run_dir(c['run'])
            src, t0 = os.path.join(d, 'pane.mkv'), c['after']
        else:
            info = mg.run_info(c['run'], c.get('align', 'onset'))
            src, t0 = info['video'], info['onset'] + c['after']
        geo, h = geometry(c['crop'])
        cut = ['-ss', f'{t0:.3f}', '-t', f'{DUR:.3f}', '-i', src]
        ref_sdr = os.path.join(work, 'ref-sdr.rgb24')
        tm = 'format=yuv444p,format=rgb24' if pane else TONEMAP_RGB
        sh(['ffmpeg', '-hide_banner', '-v', 'error', '-y'] + cut +
           ['-vf', f'fps={FPS},{tm.replace(",format=rgb24", "")},{geo}scale={W}:{h}:flags=lanczos,format=rgb24',
            '-frames:v', str(int(FPS * DUR)), '-f', 'rawvideo', ref_sdr])
        ref_hdr = None
        if not pane:
            ref_hdr = os.path.join(work, 'ref-hdr.rgb48')
            sh(['ffmpeg', '-hide_banner', '-v', 'error', '-y'] + cut +
               ['-vf', f'fps={FPS},{geo}scale={W}:{h}:flags=lanczos:in_color_matrix=bt2020:in_range=tv:out_range=pc,format=rgb48le',
                '-frames:v', str(int(FPS * DUR)), '-f', 'rawvideo', ref_hdr])
        rows = []

        def row(fmt, setting, path, dyn, scores, extra=None):
            r = dict(clip=name, format=fmt, setting=setting, range=dyn, bytes=os.path.getsize(path),
                     kbps=round(os.path.getsize(path) * 8 / DUR / 1000))
            r.update(scores)
            r.update(extra or {})
            rows.append(r)
            print(json.dumps(r), flush=True)

        # --- GIF exactly as shipped (menu-gifs.py's chain)
        gif = os.path.join(work, 'clip.gif')
        mg.gif(src, t0, DUR, None, gif, crop=c['crop'], denoise=c['denoise'], sdr=pane)
        dec = gif + '.rgb24'
        decode_raw(gif, dec, 'rgb24')
        row('gif', '96 colours, lossy 100', gif, 'sdr', score_sdr(dec, ref_sdr, W, h), java_bench(gif))

        # lossless intermediates for the encoders
        apng_sdr = os.path.join(work, 'sdr.apng')
        sh(['ffmpeg', '-hide_banner', '-v', 'error', '-y'] + raw_in(ref_sdr, 'rgb24', W, h) +
           ['-c:v', 'apng', '-plays', '0', '-pred', 'mixed', '-f', 'apng', apng_sdr])
        row('apng', 'lossless 8-bit', apng_sdr, 'sdr', dict(vmaf=100.0, ssim=1.0))
        if ref_hdr:
            apng_hdr = os.path.join(work, 'hdr.apng')
            sh(['ffmpeg', '-hide_banner', '-v', 'error', '-y'] + raw_in(ref_hdr, 'rgb48le', W, h) +
               ['-color_primaries', 'bt2020', '-color_trc', 'smpte2084', '-c:v', 'apng', '-plays', '0', '-pred', 'mixed',
                '-f', 'apng', apng_hdr])
            row('apng', 'lossless 16-bit PQ', apng_hdr, 'hdr', {})
            tmref = os.path.join(work, 'ref-hdr-tm.rgb24')
            tonemap_raw(ref_hdr, tmref, W, h)
            rows.append(dict(clip=name, format='(floor)', setting='HDR reference, tone-mapped after scaling', range='hdr',
                             bytes=0, kbps=0, sdr_view=score_sdr(tmref, ref_sdr, W, h)))

        # --- animated WebP (SDR only: VP8 is 8-bit 4:2:0)
        for q in (75, 90):
            p = os.path.join(work, f'webp-q{q}.webp')
            sh(['ffmpeg', '-hide_banner', '-v', 'error', '-y'] + raw_in(ref_sdr, 'rgb24', W, h) +
               ['-c:v', 'libwebp_anim', '-quality', str(q), '-compression_level', '6', '-loop', '0', p])
            dec = p + '.rgb24'
            decode_raw(p, dec, 'rgb24')
            row('webp', f'q{q}', p, 'sdr', score_sdr(dec, ref_sdr, W, h), java_bench(p))

        # --- JPEG XL (cjxl from the APNG; intra-only frames)
        for dist in (1.0, 2.0, 3.0):
            p = os.path.join(work, f'jxl-sdr-d{dist:g}.jxl')
            sh(['cjxl', apng_sdr, p, '-d', str(dist), '-e', '7', '--quiet'])
            dec = p + '.rgb24'
            decode_raw(p, dec, 'rgb24')
            jd = p + '.java.rgb48'
            jb = java_bench(p, jd)
            if os.path.exists(jd):
                r48 = p + '.rgb48'
                decode_raw(p, r48, 'rgb48le')
                jb.update(exact(jd, r48, W, h))
                os.unlink(jd)
            row('jxl', f'd{dist:g}', p, 'sdr', score_sdr(dec, ref_sdr, W, h), jb)
            if ref_hdr:
                p = os.path.join(work, f'jxl-hdr-d{dist:g}.jxl')
                sh(['cjxl', apng_hdr, p, '-d', str(dist), '-e', '7', '--quiet', '-x', 'color_space=RGB_D65_202_Rel_PeQ'])
                dec = p + '.rgb48'
                decode_raw(p, dec, 'rgb48le')
                tmd = p + '.tm.rgb24'
                tonemap_raw(dec, tmd, W, h)
                jd = p + '.java.rgb48'
                jb = java_bench(p, jd)
                if os.path.exists(jd):
                    jb.update(exact(jd, dec, W, h))
                    os.unlink(jd)
                row('jxl', f'd{dist:g}', p, 'hdr', dict(score_hdr(dec, ref_hdr, W, h), sdr_view=score_sdr(tmd, ref_sdr, W, h)), jb)

        # --- AVIF (avifenc / libaom from a y4m: an animated AVIF sequence, inter-coded; ffmpeg's own avif muxer writes an
        # edit list in the movie timescale that javif 0.1.0 compares with the media timescale and rejects)
        for chroma in ('444', '420'):
            for q in (45, 60, 75):
                for dyn in ('sdr', 'hdr'):
                    if dyn == 'hdr' and not ref_hdr:
                        continue
                    p = os.path.join(work, f'avif-{dyn}-{chroma}-q{q}.avif')
                    avif_encode(ref_sdr if dyn == 'sdr' else ref_hdr, W, h, p, dyn == 'hdr', chroma, q)
                    if dyn == 'sdr':
                        dec = p + '.rgb24'
                        decode_raw(p, dec, 'rgb24')
                    r48 = p + '.rgb48'
                    decode_raw(p, r48, 'rgb48le')
                    jd = p + '.java.rgb48'
                    jb = java_bench(p, jd)
                    if os.path.exists(jd):
                        jb.update(exact(jd, r48, W, h))
                        os.unlink(jd)
                    if dyn == 'sdr':
                        row('avif', f'8-bit {chroma} q{q}', p, 'sdr', score_sdr(dec, ref_sdr, W, h), jb)
                    else:
                        tmd = p + '.tm.rgb24'
                        tonemap_raw(r48, tmd, W, h)
                        row('avif', f'10-bit {chroma} q{q}', p, 'hdr',
                            dict(score_hdr(r48, ref_hdr, W, h), sdr_view=score_sdr(tmd, ref_sdr, W, h)), jb)
        results[name] = rows
        for f in os.listdir(work):  # keep the encodes, drop the raw frames
            if f.endswith(('.rgb24', '.rgb48')) and not f.startswith('ref-'):
                os.unlink(os.path.join(work, f))
    path = os.path.join(out_dir, 'results.json')
    old = json.load(open(path)) if os.path.exists(path) else {}
    old.update(results)
    json.dump(old, open(path, 'w'), indent=1)
    print('wrote', path)


if __name__ == '__main__':
    run(sys.argv[1], sys.argv[2:] or list(EVAL))
