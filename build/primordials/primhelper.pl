#!/usr/bin/env perl
#  This file is part of the Jikes RVM project (http://jikesrvm.org).
#
#  This file is licensed to You under the Common Public License (CPL);
#  You may not use this file except in compliance with the License. You
#  may obtain a copy of the License at
#
#      http://www.opensource.org/licenses/cpl1.0.php
#
#  See the COPYRIGHT.txt file distributed with this work for information
#  regarding copyright ownership.
#
#  @author: Georgios Gousios <gousiosg@gmail.com>, Jul 2008

use strict;

#Need at least 1 arg
if ( not defined $ARGV[0] ) {
  die usage();
}

my @allfiles;
my @files;

recurse( $ARGV[0] );

my %seen = ();

while ( defined( my $line = <STDIN> ) ) {

  chop $line;
   $line =~ s/\.\*/.[^\/]*\$/;
  #printf "Pattern ".$line."\n";
  foreach my $file (@allfiles) {
    if ( $file =~ m/^$line/ ) {
      #print "Adding primordial file " . $file . "\n";
      push( @files, $file ) unless $seen{$file}++;
    }
  }
}

my @primordials;
my $package;

foreach my $file (@files) {
  $file = $ARGV[0] . "/" . $file;
  open( FILE, "<", $file ) or die "can't open $file: $!";

  #print "File:".$file."\n";
  my $mainclass = undef;
  my $pattern = '^[^*\/]*(class|(@)?interface|enum) ([\w][\w\d_]*)\s*(<.*>)?.*$';
  while (<FILE>) {
    m/^\s*package (.*);\s*$/ && ( $package = $1 );
    
    if (not defined $mainclass and ($_ =~ m/$pattern/)) {
      push( @primordials, $package.".".$3 );
      #print "Found main class:". $package."." . $3 . "\n";
      $mainclass = $3;
      next;
    }
    
    if (defined $mainclass and ($_ =~ m/$pattern/)) {
      push( @primordials, $package.".".$mainclass."\$".$3 );
      #print "  Found internal class:" . $mainclass."\$".$3 . "\n";
    }
  }
  if (not defined $mainclass) {
    print STDERR "Warning: no class definition found in file: ".$file."\n";
  }
}

foreach my $item (@primordials) {
  print "L".$item.";\n";
}

#Recurse into subdirs and find all java files
sub recurse {
  my ($path) = @_;
  my $pattern = $ARGV[0] . "(.*)\$";
  ## append a trailing / if it's not there
  $path .= '/' if ( $path !~ /\/$/ );

  ## loop through the files contained in the directory
  for my $eachFile ( glob( $path . '*' ) ) {

    ## if the file is a directory
    if ( -d $eachFile ) {
      ## pass the directory to the routine ( recursion )
      recurse($eachFile);
    }
    else {
      if ( $eachFile =~ /.java$/ ) {
        $eachFile =~ s/$pattern/$1/;

        #print "Adding file:",$eachFile,"\n";
        push( @allfiles, $eachFile );
      }
    }
  }
}

sub usage() {
  print << "END";
primhelper.pl - Reads a list of classnames from STDIN, finds the class
declarations in classpath-dir, extracts any private classes and returns
the result in primordial friendly format. 

usage: primhelper.pl classpath-dir

END
}
