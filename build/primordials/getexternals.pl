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

#Need at least 2 args
if ( not defined $ARGV[0] or not defined $ARGV[1] ) {
  die usage();
}

my @allfiles;
my @allclasses;

#Get all Java files in the provided directories
for ( my $i = 0 ; $i < $#ARGV ; $i++ ) {
  push( @allfiles, recurse( $ARGV[$i] ) );
}

#Get all imported classes in a list
@allclasses = getimports(@allfiles);

my %seen = ();
my @externals;

#Remove doublicates
foreach my $item (@allclasses) {
  push( @externals, $item ) unless $seen{$item}++;
}

#Print result
foreach my $item (@externals) {
  print $item. "\n";
}

#Get the list of external classes
sub getimports {

  my (@files) = @_;
  my @classes, my @tmpimports;
  my $package;

  #Read files and store all imports in a
  #temporary list
  foreach my $file (@files) {
    open( FILE, "<", $file ) or die "can't open $file: $!";

    #print "File:".$file."\n";
    while (<FILE>) {
      m/^\s*package (.*);\s*$/ && ( $package = $1 );

      #m/^\s*package (.*);\s*$/ && print "Found package:".$1."\n";
      m/^\s*import\s*(static)?\s?(.*);\s*$/ && ( push @tmpimports, $2 );

      #m/^\s*import\s*(static)?\s?(.*);\s*$/ && print "Found import:".$2."\n";

      if (m/^.*(class|(@)?interface) ([A-Z].*?)\s*(<.*>)? .*{?\s*}?\s*$/) {

        #print $_;
        if ( $package eq "" ) {
          push( @classes, $3 );

          #print "Found class:" . $3 . "\n";
        }
        else {
          push( @classes, $package . "." . $3 );

          #print "Found class:" . $package . "." . $3 . "\n";
        }
      }
    }
  }

  #Get a list of unique package names. This might include
  #class names in case of static field imports
  my @tmpinclpkg;
  %seen = ();
  foreach my $import (@tmpimports) {
    if ( $import =~ m/.*\.(.*)\..*$/ ) {

      #print("Package:".$1."\n");
      push( @tmpinclpkg, $1 ) unless $seen{$1}++;
    }
  }

  #For each potential enclosing package name, check if there is a
  #class with the same name. If there is one, this means that this
  #class contains static fields which are used in import declarations
  #in other classes.
  my @expst;
  %seen = ();
  foreach my $pkgname (@tmpinclpkg) {
    foreach my $class (@classes) {
      my $pattern = ".*" . $pkgname . "\$";
      if ( $class =~ m/$pattern/ ) {
        push( @expst, $class ) unless $seen{$class}++;

        #printf("Class with exported static fields:".$class."\n" );
      }
    }
  }

  #Clean up imports satisfied by classes in this source dir
  #Also remove duplicates and static class imports
  %seen = ();
  my @allimports;

  foreach my $import (@tmpimports) {
    my $found = 0;
    my $class;
    foreach $class (@classes) {
      if ( $class eq $import ) {
        $found = 1;
        last;
      }
    }

    foreach my $exportstatic (@expst) {
      if ( $import =~ m/$exportstatic/ ) {
        $found = 1;
        last;
      }
    }

    if ( $found == 0 ) {
      push( @allimports, $import ) unless $seen{$import}++;
    }
  }

  return @allimports;
}

my @somefiles;

#Recurse into subdirs and find all java files
sub recurse {
  my ($path) = @_;

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

        #print "Adding file:",$eachFile,"\n";
        push( @somefiles, $eachFile );
      }
    }
  }

  return @somefiles;
}

sub usage() {
  print << "END";
getexternals.pl - Parses the Java files found in the directories provided
as arguments and returns a list of classnames that are used but not
declared in either of those directories.

usage: getexternals.pl dir1...dirn

END
}
