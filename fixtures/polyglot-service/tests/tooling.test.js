import { strict as assert } from 'node:assert';
import { test } from 'node:test';

import { format } from '../src/tooling.js';

test('format renders minor units as a decimal string', () => {
  assert.equal(format(12345), '123.45');
});
