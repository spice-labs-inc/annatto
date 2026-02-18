#!/usr/bin/env ruby
# Extracts metadata from a .podspec or .podspec.json file
require 'json'
podspec_file = ARGV[0]
if podspec_file.end_with?('.json')
  puts File.read(podspec_file)
else
  # Use pod ipc spec for Ruby DSL podspecs
  output = `pod ipc spec "#{podspec_file}" 2>/dev/null`
  puts output
end
