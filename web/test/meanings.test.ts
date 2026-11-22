// Port check for LikelyMeanings.kt: every case in fixtures/likely-meanings.json (inputs and the Kotlin result).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import cases from '../../fixtures/likely-meanings.json' with { type: 'json' };
import { type StoredForm, buildLikelyMeanings, isEmptyMeanings, meaningKey } from '../src/core/meanings.ts';

for (const c of cases) {
  test(`likely meanings match Kotlin: ${c.name}`, () => {
    assert.deepEqual(buildLikelyMeanings(c.own as StoredForm, c.candidates as StoredForm[]), c.expected);
  });
}

test('the fixture covers the interesting cases', () => {
  const byName = new Map(cases.map((c) => [c.name, c.expected]));
  assert.ok(isEmptyMeanings(byName.get('empty')!));
  assert.ok(byName.get('related forms')!.meanings.some((m) => m.forms.length > 0));
  assert.ok(byName.get('merge and rank')!.meanings[0].count > 1);
  assert.ok(byName.get('many related forms')!.relatedForms.length > 0);
});

test('meaning keys fold case, spacing and punctuation but keep accents', () => {
  assert.equal(meaningKey(' Bank. '), 'bank');
  assert.equal(meaningKey('to go (somewhere)'), 'to go somewhere');
  assert.equal(meaningKey('Ñandú'), 'ñandú');
  assert.equal(meaningKey('...'), null);
  assert.equal(meaningKey(null), null);
});
