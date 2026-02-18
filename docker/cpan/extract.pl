#!/usr/bin/env perl
# Extracts metadata from a CPAN distribution .tar.gz (META.json or META.yml)
use strict;
use warnings;
use CPAN::Meta;
use JSON::PP;
use File::Temp qw(tempdir);

my $dist_file = $ARGV[0];
my $tmpdir = tempdir(CLEANUP => 1);
system("tar", "xzf", $dist_file, "-C", $tmpdir) == 0 or die "tar failed: $!";
my @meta_files = glob("$tmpdir/*/META.json $tmpdir/*/META.yml");
die "No META.json or META.yml found\n" unless @meta_files;
my $meta = CPAN::Meta->load_file($meta_files[0]);
print JSON::PP->new->pretty->encode({
    name => $meta->name,
    version => $meta->version,
    abstract => $meta->abstract,
    license => [$meta->licenses],
    author => [$meta->authors],
    prereqs => $meta->prereqs->as_string_hash,
});
