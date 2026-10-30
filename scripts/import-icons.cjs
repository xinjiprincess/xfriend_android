// Import the exact Lucide paths used by the desktop, preserving its ISC license.
const fs = require('node:fs');
const path = require('node:path');
const root = process.argv[2] || 'D:/octop/dashboard/node_modules/lucide-react';
const out = path.resolve(__dirname, '../app/src/main/res/drawable');
for (const name of ['message-square-text','graduation-cap','users','package','database','link-2','settings','menu']) {
  const text = fs.readFileSync(path.join(root, 'dist/esm/icons', name + '.js'), 'utf8');
  const body = text.split('const __iconNode = ')[1].split('\n];')[0] + '\n]';
  const nodes = require('node:vm').runInNewContext(body);
  const paths = nodes.map(([type, a]) => {
    if (type === 'path') return a.d;
    if (type === 'circle') return `M${+a.cx - +a.r},${a.cy}a${a.r},${a.r} 0,1 0,${2*a.r},0a${a.r},${a.r} 0,1 0,-${2*a.r},0`;
    if (type === 'ellipse') return `M${+a.cx - +a.rx},${a.cy}a${a.rx},${a.ry} 0,1 0,${2*a.rx},0a${a.rx},${a.ry} 0,1 0,-${2*a.rx},0`;
    if (type === 'line') return `M${a.x1},${a.y1}L${a.x2},${a.y2}`;
    if (type === 'polyline' || type === 'polygon') return 'M' + a.points.trim().replace(/\s+/g,'L') + (type === 'polygon' ? 'Z' : '');
    throw Error('Unhandled shape: '+type);
  });
  fs.writeFileSync(path.join(out,'lucide_'+name.replaceAll('-','_')+'.xml'), `<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n` + paths.map(d=>`<path android:pathData="${d}" android:strokeColor="#111827" android:strokeWidth="1.8" android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="@android:color/transparent" />`).join('\n')+'\n</vector>\n');
}
fs.copyFileSync(path.join(root,'LICENSE'), path.resolve(__dirname,'../docs/LUCIDE-LICENSE.txt'));
