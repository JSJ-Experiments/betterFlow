import { exec, toast } from 'kernelsu';

const MOD = '/data/adb/modules/betterflow';
const ctl = (verb) => `MODDIR=${MOD} sh ${MOD}/scripts/control.sh ${verb}`;
const $ = (id) => document.getElementById(id);

function parseKv(text) {
  return Object.fromEntries(text.split(/\r?\n/).filter(Boolean).map((line) => {
    const i = line.indexOf('=');
    return i < 0 ? [line, ''] : [line.slice(0, i), line.slice(i + 1)];
  }));
}

async function run(command) {
  const result = await exec(command);
  if (result.errno !== 0) throw new Error(result.stderr || `command failed (${result.errno})`);
  return result.stdout || '';
}

async function refresh() {
  try {
    const s = parseKv(await run(ctl('status')));
    $('version').textContent = `${s.version || 'unknown'} (${s.versionCode || '?'})`;
    $('background').textContent = 'off';
    $('app').textContent = s.appPid === 'stopped' ? 'stopped' : `pid ${s.appPid}`;
    $('bubble').textContent = s.bubble || 'unknown';
    $('dot').className = `dot ${s.appPid && s.appPid !== 'stopped' ? 'ok' : 'bad'}`;
  } catch (error) {
    $('dot').className = 'dot bad';
    $('output').textContent = String(error);
  }
}

$('refresh').onclick = refresh;
$('update').onclick = async () => {
  $('update').disabled = true;
  $('output').textContent = 'Checking GitHub release…';
  try {
    const output = await run(ctl('update'));
    $('output').textContent = output || 'Update completed.';
    toast('betterFlow hot update applied');
  } catch (error) {
    $('output').textContent = String(error);
    toast('betterFlow update failed');
  } finally {
    $('update').disabled = false;
    await refresh();
  }
};

for (const [id, verb] of [
  ['settings', 'settings'],
  ['start', 'start'],
  ['stop', 'stop'],
]) {
  $(id).onclick = () => run(ctl(verb)).then(refresh).catch((error) => toast(String(error)));
}

refresh();
