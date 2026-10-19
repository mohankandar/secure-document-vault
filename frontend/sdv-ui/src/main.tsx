// R7 design preview remains isolated from the authenticated application.
// Default route retains the approved R5 application and its real backend APIs.
const root = document.getElementById('root')!
const preview = new URLSearchParams(window.location.search).get('prototype') === 'r7'
const entry = preview ? import('./r7/entry') : import('./legacy-entry')
void entry.then((module) => module.mount(root))
