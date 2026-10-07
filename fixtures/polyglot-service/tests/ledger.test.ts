import { strict as assert } from 'node:assert';
import { test } from 'node:test';

import { total } from '../src/ledger.ts';

test('total adds the amounts', () => {
  assert.equal(total([100, 250]), 350);
});
