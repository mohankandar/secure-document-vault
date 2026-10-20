// Design branch: R7 prototype is the default; the approved, backend-connected
// R5 app stays available unchanged at ?app=r5. Separate dynamic entries keep the
// two style systems and code paths fully isolated.
const root = document.getElementById('root')!
const entry = new URLSearchParams(window.location.search).get('app') === 'r5' ? import('./legacy-entry') : import('./r7/entry')
void entry.then((m) => m.mount(root))
