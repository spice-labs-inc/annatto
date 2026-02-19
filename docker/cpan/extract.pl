#!/usr/bin/env perl
# Extracts metadata from CPAN distribution .tar.gz files and outputs
# the Annatto source-of-truth JSON schema.
#
# Usage: extract.pl [--batch] <package-file> [...]
# In batch mode, outputs to /work/out/<basename>-expected.json.
use strict;
use warnings;
use CPAN::Meta;
use JSON::PP;
use File::Temp qw(tempdir);
use File::Basename;

my $batch_mode = 0;
my @files;
for my $arg (@ARGV) {
    if ($arg eq '--batch') {
        $batch_mode = 1;
    } else {
        push @files, $arg;
    }
}

die "Usage: extract.pl [--batch] <package-file> [...]\n" unless @files;

my $json_pp = JSON::PP->new->utf8->canonical->pretty;

for my $dist_file (@files) {
    my $result = extract_metadata($dist_file);

    if ($batch_mode) {
        my $base = basename($dist_file);
        $base =~ s/\.tar\.gz$//;
        my $out_path = "/work/out/${base}-expected.json";
        open my $fh, '>', $out_path or die "Cannot write $out_path: $!";
        print $fh $json_pp->encode($result);
        close $fh;
        print "Wrote: $out_path\n";
    } else {
        print $json_pp->encode($result);
    }
}

sub extract_metadata {
    my ($dist_file) = @_;
    my $tmpdir = tempdir(CLEANUP => 1);

    system("tar", "xzf", $dist_file, "-C", $tmpdir) == 0
        or return { error => "tar extraction failed for $dist_file" };

    # Find META.json or META.yml (prefer .json)
    my @meta_json = glob("$tmpdir/*/META.json");
    my @meta_yml  = glob("$tmpdir/*/META.yml");

    my $meta;
    if (@meta_json) {
        eval { $meta = CPAN::Meta->load_file($meta_json[0]); };
        if ($@) {
            return { error => "Failed to parse META.json: $@" };
        }
    } elsif (@meta_yml) {
        eval { $meta = CPAN::Meta->load_file($meta_yml[0]); };
        if ($@) {
            return { error => "Failed to parse META.yml: $@" };
        }
    } else {
        return { error => "No META.json or META.yml found in $dist_file" };
    }

    my $name = $meta->name;
    my $version = $meta->version;
    # Convert version object to string
    $version = "$version" if ref $version;

    # Description: abstract field
    my $abstract = $meta->abstract;
    $abstract = undef if defined $abstract && $abstract eq '';
    # Filter out placeholder abstracts
    $abstract = undef if defined $abstract && $abstract eq 'unknown';

    # License: join with " OR "; ["unknown"] -> null
    my @licenses = $meta->licenses;
    my $license = undef;
    if (@licenses && !(@licenses == 1 && $licenses[0] eq 'unknown')) {
        $license = join(' OR ', @licenses);
    }

    # Publisher: join authors with ", "
    my @authors = $meta->authors;
    my $publisher = undef;
    if (@authors) {
        $publisher = join(', ', @authors);
        $publisher = undef if $publisher eq '' || $publisher eq 'unknown';
    }

    # publishedAt: always null (not in META.json)
    my $published_at = undef;

    # Dependencies: phases x relationships (only 'requires')
    my @dependencies;
    my $prereqs = $meta->effective_prereqs;
    my %phase_to_scope = (
        runtime   => 'runtime',
        test      => 'test',
        build     => 'build',
        configure => 'build',
        develop   => 'dev',
    );

    for my $phase (sort keys %phase_to_scope) {
        my $scope = $phase_to_scope{$phase};
        my $reqs = $prereqs->requirements_for($phase, 'requires');
        next unless $reqs;
        my $req_hash = $reqs->as_string_hash;
        next unless $req_hash;
        for my $dep_name (sort keys %$req_hash) {
            next if $dep_name eq 'perl';  # Skip perl itself
            my $ver = $req_hash->{$dep_name};
            my $vc = undef;
            if (defined $ver && $ver ne '0') {
                $vc = $ver;
            }
            push @dependencies, {
                name             => $dep_name,
                versionConstraint => $vc,
                scope            => $scope,
            };
        }
    }

    # simpleName is same as name for CPAN (dist name)
    return {
        name         => $name,
        simpleName   => $name,
        version      => "$version",
        description  => $abstract,
        license      => $license,
        publisher    => $publisher,
        publishedAt  => $published_at,
        dependencies => \@dependencies,
    };
}
