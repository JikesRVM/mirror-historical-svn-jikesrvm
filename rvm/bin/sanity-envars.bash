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

# Who we are.  Just the short form of the name (no directory component).
ME="${0##*/}"

## Usage: checkenv <envar> [ <example> ]
## If the environment variable <envar> is unset, then tell the user it
## should be set, suggest an example value (if <example> is set), and
## exit fatally.
function checkenv () {
    local var="$1";
    local example="$2";

    if [ ! "${!var}" ]; then
	echo -n "$ME: Please set your ${var} environment variable"
	if [ "$example" ]; then
	    echo "";
	    echo -n "  (for example, to \"${example}\")"
	fi
	echo "."		# Period for grammar.
	exit 1
    fi >&2
}
