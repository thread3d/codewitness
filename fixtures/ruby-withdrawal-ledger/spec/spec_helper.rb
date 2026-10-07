# frozen_string_literal: true

# RSpec loads this before any spec file, including the ones CodeWitness generates into
# `spec/generated/`. Those open with `require 'spec_helper'`, so this file is what puts the ledger on the
# load path for them as well as for the fixture's own suite.
require "withdrawal_ledger"
