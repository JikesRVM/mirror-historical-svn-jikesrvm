#! /bin/bash
#
# (C) Copyright IBM Corp. 2001, 2003, 2003
#
# $Id$
#
# N.B.: This is an auxiliary function for loading.
#       We do not execute this script directly; the `#! /bin/bash'
#	is just there to give a hint to the text editor (or at least to
#	Emacs) about what mode to go into.
#
# @author Steven Augart
# @date  Sunday, June 1, 2003

## KSH-compatible version of print.  
## This can go away as soon as we replace all the remaining calls to "print"
## in our shell programs.
print()
{
    local eflag=-e
    local nflag= fflag= c
    local fd=1

    OPTIND=1
    while getopts "fRnprsu:" c
    do
	case $c in
	R)	eflag= ;;
	r)      eflag= ;;
	n)      nflag=-n ;;
	s)      sflag=y ;;
	f)      fflag=y ;;
	u)      fd=$OPTARG ;;
	p)      ;;
	esac
    done
    shift $(( $OPTIND - 1 ))

    if [ -n "$fflag" ]; then
	builtin printf "$@" >&$fd
	return
    fi

    case "$sflag" in
    y)      builtin history -s "$*" ;;
    *)      builtin echo $eflag $nflag "$@" >&$fd
    esac
}

