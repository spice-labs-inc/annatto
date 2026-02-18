#!/usr/bin/env ruby
# Extracts metadata from a .gem file and outputs Annatto source-of-truth JSON.
# Uses Gem::Package.new(gem_file).spec for native Ruby gem parsing.
# Usage: extract.rb <file.gem>
# Output: JSON to stdout matching Annatto schema:
#   {name, simpleName, version, description, license, publisher, dependencies}
require 'json'
require 'rubygems/package'

gem_file = ARGV[0]
spec = Gem::Package.new(gem_file).spec

# Description: prefer summary, fall back to description if summary is nil/empty
description = if spec.summary && !spec.summary.strip.empty?
                spec.summary
              elsif spec.description && !spec.description.strip.empty?
                spec.description
              else
                nil
              end

# License: join licenses with " OR ", null if empty
license = if spec.licenses && !spec.licenses.empty?
             spec.licenses.join(" OR ")
           else
             nil
           end

# Publisher: first author, null if empty
publisher = if spec.authors && !spec.authors.empty? && spec.authors.first && !spec.authors.first.strip.empty?
              spec.authors.first
            else
              nil
            end

# Dependencies: ALL deps (runtime + dev)
dependencies = spec.dependencies.map do |d|
  req_str = d.requirement.to_s
  version_constraint = (req_str == ">= 0") ? nil : req_str
  scope = case d.type
          when :runtime then "runtime"
          when :development then "dev"
          else "runtime"
          end
  { name: d.name, versionConstraint: version_constraint, scope: scope }
end

result = {
  name: spec.name,
  simpleName: spec.name,
  version: spec.version.to_s,
  description: description,
  license: license,
  publisher: publisher,
  dependencies: dependencies
}

puts JSON.pretty_generate(result)
