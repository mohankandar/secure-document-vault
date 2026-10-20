// R7 is the sole frontend entry for the Business User / Document Steward candidate.
const root=document.getElementById('root')!
void import('./r7/entry').then(m=>m.mount(root))
