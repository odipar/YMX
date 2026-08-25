# House style

Rules for prose: documents, code comments, commit messages. Each appears once.

## Plain words

Standard terms, not coinages. A compound that says one thing twice is a term
the reader has to decode, and the plain description is shorter than the
compound it replaces.

A noun pressed into service as a verb is the same fault. A repository does
not *vendor* a library: it carries a copy of one, and the copy is what the
sentence is about. Write what happened - copied here, carried here, kept
here - and the reader needs no glossary.

## One vocabulary

Where a project defines its terms, those are the names. A term that changes in
the glossary changes in the code the same day, or there are two vocabularies
to keep in step.

## Nothing acts on its own

No file, program or algorithm wants, knows, decides, expects or refuses. The
plain verb is there: a source *needs*, a header *declares*, a stage
*resolves*, a reader *does not validate*.

Roles and abstractions follow the rule. A writer does not *promise*, a
document section does not *keep* bits, a verb does not *consume* its
operand, bits do not *stand as they were*. The writer emits, the section
lists, the verb reads, the bits hold their value.

Established technical vocabulary is not this. A resource has an *owner*, a
caller *claims* it, a register *survives* a call.

## Say it once

Four habits that state an idea twice:

- **three of a kind.** `no stale value, no zero, no bus cycle` - say what
  happens and stop.
- **the cleft.** `X is what makes Y` is `X makes Y`.
- **the restatement.** `- which is a compile-time edit` is `- a compile-time
  edit`. Drop `which is` where a comma already carries the appositive; keep it
  before a predicate (`which is true whether…`) or an explanation (`which is
  why…`).
- **filler.** `simply`, `actually`, `precisely`, `entirely`, `at all` - cut
  unless the word carries the meaning: `exactly` for an equality, `entirely in
  memory` for the absence of a file on disk.

Keep a list only where each item carries something the others do not.

## No flourish

Technical prose states the operation and ends. Three habits that decorate
instead:

- **the sweep.** `whatever value is written`, `wherever it sits` - a
  trailing clause that generalises what the sentence already said. Name the
  condition or end the sentence.
- **the metaphor.** `leaves a tail no reader ever touches`, `the pressure
  point` - an image in place of the operation. Write the operation.
- **the verdict.** `this is deliberate`, `asked properly`, `worth reading` -
  the sentence grading itself or its subject. Delete it.

## The verb that says the action

Something *uses* a resource, a bit *marks* a case, a header *flags* a state, a
code *selects* an option, a field *gives* a value. Reserve *names* for what a
thing is called.

## A specification states operations

Describe what happens, in terms an implementer can check: what is written, in
what order, and what is left alone. Name no product, routine or source file -
an implementation follows the specification, not the other way round. A rule
that needs a cross-reference to be understood is not yet stated operationally.

## True beats accurate

A sentence that is literally correct but implies something false is wrong. A
figure given without the comparison that makes it meaningful misleads as much
as a wrong figure.

## Measure, do not recall

Check a claim against the thing it describes before writing it. Where a number
has to appear in prose, have a test read it back out and fail when the
sentence carrying it is reworded away.

## One idea per row

Two things in one table cell get a row each, and a heading that covers two
subjects gets split. A column means one thing from top to bottom; where a row
needs a different convention, the cell says so.

## Shape

Wrap at one width and hold it. Rewrap the paragraph you changed and no other:
a blanket reflow buries the words that moved.

No em dash construct anywhere: a dash that must stay is a single `-`.
