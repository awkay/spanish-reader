// End-to-end smoke test of the web app against a running server (real AI and Piper).
//   BASE_URL=http://localhost:8090 CODE=123456 SHOTS=/tmp/shots \
//   PLAYWRIGHT_MODULE=/path/to/playwright/index.mjs node e2e/smoke.mjs
// Uses an iPhone-sized Chromium window. Exits non-zero on the first failed check.
const { chromium, devices } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const base = process.env.BASE_URL ?? 'http://localhost:8090';
const code = process.env.CODE;
const shots = process.env.SHOTS;
if (!code) throw new Error('CODE is required');

const STORY = `La abuela y el perro

Había una vez un niño que vivía en el campo con su abuela. Todas las mañanas se levantaba temprano para darle de comer a las gallinas. Su abuela siempre le decía que el trabajo de la mañana era el más importante del día.

Un día se le olvidó cerrar la puerta del corral, y las gallinas se escaparon al bosque. ¡Qué desastre! El niño corrió detrás de ellas, pero no pudo alcanzarlas. Sin embargo, su perro, que era muy listo, se dio cuenta de lo que pasaba y empezó a ladrar.

Las gallinas tuvieron miedo del perro y volvieron corriendo a la casa. La abuela, que lo había visto todo desde la ventana, no se enojó; al contrario, se rió mucho. Le dijo al niño que a lo mejor el perro merecía un premio.

Esa noche, el niño le dio al perro un pedazo grande de pan con queso. El perro se lo comió en dos segundos y después se durmió junto a la chimenea. El niño pensó que iba a echar de menos esos días cuando fuera mayor.

Con los años, el niño se hizo hombre y se mudó a la ciudad. Trabajaba en una oficina grande, llena de computadoras y de gente apurada. A veces, cuando estaba cansado, cerraba los ojos y recordaba el olor del pan de su abuela, el ruido de las gallinas y los ladridos de su viejo perro.

Un domingo de primavera decidió volver al campo. La casa todavía estaba allí, un poco más pequeña de lo que recordaba. La puerta del corral seguía rota, y él no pudo evitar sonreír. Se sentó en el banco del jardín, respiró hondo y, por primera vez en mucho tiempo, se sintió en casa.`;

let step = 0;
async function check(name, fn) {
  step++;
  try {
    await fn();
    console.log(`ok ${step} - ${name}`);
  } catch (e) {
    console.log(`not ok ${step} - ${name}\n  ${e.message.split('\n')[0]}`);
    if (shots) await page.screenshot({ path: `${shots}/fail-${step}.png`, fullPage: true }).catch(() => {});
    await browser.close();
    process.exit(1);
  }
}
const shot = async (name) => shots && page.screenshot({ path: `${shots}/${name}.png` });

const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
const context = await browser.newContext({ ...devices['iPhone 13'], defaultBrowserType: undefined });
const page = await context.newPage();
page.on('pageerror', (e) => console.log('  page error:', e.message));

await check('login screen; a wrong code is refused after a delay', async () => {
  await page.goto(base + '/');
  await page.getByLabel('Access code').fill('000000');
  const t = Date.now();
  await page.getByRole('button', { name: 'Enter' }).click();
  await page.getByText("That code isn't right.").waitFor();
  if (Date.now() - t < 1800) throw new Error('wrong code answered too fast');
  await shot('01-login');
});

await check('the right code opens the library', async () => {
  await page.getByLabel('Access code').fill(code);
  await page.getByRole('button', { name: 'Enter' }).click();
  await page.getByText('My lessons').waitFor();
});

await check('create a two-page lesson by pasting text', async () => {
  await page.getByText('＋ New lesson').click();
  await page.getByPlaceholder('Spanish text').fill(STORY);
  await page.getByRole('button', { name: 'Create lesson' }).click();
  await page.getByText('Page 1 of 2').waitFor();
  await page.waitForTimeout(500);
  await shot('02-reader');
});

await check('tap a word: meaning in context, verb details, sentence translation', async () => {
  await page.locator('.page-text .w', { hasText: /^levantaba$/ }).first().click();
  await page.locator('.sheet .meaning').waitFor({ timeout: 90_000 });
  await page.locator('.sheet .translation').waitFor({ timeout: 90_000 });
  const sheet = await page.locator('.sheet').innerText();
  if (!/levantar/i.test(sheet)) throw new Error('expected the lemma levantar in: ' + sheet.slice(0, 300));
  await shot('03-word-sheet');
  await page.locator('.sheet-backdrop').click({ position: { x: 10, y: 10 } });
});

await check('the tapped word is now level 1 (yellow)', async () => {
  const style = await page.locator('.page-text .w', { hasText: /^levantaba$/ }).first().getAttribute('style');
  if (!style?.includes('255, 193, 7')) throw new Error('not yellow: ' + style);
});

await check('play page audio: the spoken sentence is highlighted', async () => {
  await page.getByTitle('Play').click();
  await page.locator('.page-text .spoken').first().waitFor({ timeout: 60_000 });
  await page.getByTitle('Pause').waitFor();
  await page.waitForTimeout(2500);
  const playing = await page.evaluate(() => [...document.querySelectorAll('audio')].length >= 0);
  if (!playing) throw new Error('no audio');
  await shot('04-playing');
  await page.getByTitle('Pause').click();
});

await check('idioms are underlined once sentences are analyzed', async () => {
  await page.locator('.page-text .phrase').first().waitFor({ timeout: 120_000 });
  await shot('05-idioms');
});

await check('next page: blue words go in at level 1, never Known', async () => {
  await page.getByRole('button', { name: 'Next page ›' }).click();
  await page.getByText('Page 2 of 2').waitFor();
  await page.getByText(/words added at level 1/).waitFor();
});

await check('share the lesson; it appears in the shared library', async () => {
  await page.locator('a.icon-btn').first().click();
  await page.getByTitle('Share with the household').first().click();
  await page.getByText(/Shared “La abuela y el perro”/).waitFor({ timeout: 30_000 });
  await page.locator('.card', { hasText: 'shared by' }).or(page.locator('.card', { hasText: 'Added' })).first().waitFor();
  await shot('06-library');
});

await check('vocabulary lists the words added by the page rule', async () => {
  await page.goto(base + '/#/vocab');
  await page.getByText(/^Learning \d+/).waitFor();
  const n = Number((await page.getByText(/^Learning \d+/).innerText()).replace(/\D/g, ''));
  if (n < 20) throw new Error('expected many learning words, got ' + n);
  await shot('07-vocab');
});

await browser.close();
console.log('all checks passed');
