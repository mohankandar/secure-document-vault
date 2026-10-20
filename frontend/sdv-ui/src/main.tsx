// The approved R5 application remains the default until API parity is verified.
// ?experience=r7-live enables a real-API, R7-styled engineering preview.
// ?prototype=r7 remains a *mock* visual reference only.
const root = document.getElementById('root')!
const params = new URLSearchParams(window.location.search)
const preview = params.get('prototype') === 'r7'
const liveR7 = !preview && params.get('experience') === 'r7-live'
if (liveR7) {
  void import('./r7-live.css')
  document.documentElement.classList.add('sdv-r7-live')
}
const nativeR7 = !preview && params.get('experience') === 'r7-native'
const entry = preview ? import('./r7/entry') : nativeR7 ? import('./r7/native-entry') : import('./legacy-entry')
void entry.then((module) => module.mount(root))
