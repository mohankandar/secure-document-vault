// The Vero (R7) app. Loaded as its own chunk so its stylesheet arrives with it.
const root = document.getElementById('root')!
void import('./r7/entry').then((m) => m.mount(root))
