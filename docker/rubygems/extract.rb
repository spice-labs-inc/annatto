#!/usr/bin/env ruby
# Extracts metadata from a .gem file using gem specification
require 'json'
require 'rubygems/package'
gem_file = ARGV[0]
spec = Gem::Package.new(gem_file).spec
puts JSON.pretty_generate({
  name: spec.name,
  version: spec.version.to_s,
  summary: spec.summary,
  description: spec.description,
  licenses: spec.licenses,
  authors: spec.authors,
  dependencies: spec.dependencies.map { |d| { name: d.name, requirement: d.requirement.to_s, type: d.type.to_s } }
})
