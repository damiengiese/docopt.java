package org.docopt;

import org.docopt.Pattern.MatchResult;
import org.docopt.Python.*;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.*;

import static org.docopt.Python.*;

/**
 * Pythonic command-line interface parser that will make you smile.
 *
 * <ul>
 * <li>http://docopt.org
 * <li>Repository and issue-tracker: https://github.com/docopt/docopt
 * <li>Licensed under terms of MIT license (see LICENSE-MIT)
 * <li>Copyright (c) 2012 Vladimir Keleshev, vladimir@keleshev.com
 * <li>Copyright (c) 2014 Damien Giese, damien.giese@gmail.com
 * </ul>
 * <p>
 * Changes:
 * <ul>
 * <li>Implemented Java-style {@code equals} and {@code hashCode} methods
 * instead of relying on {@code toString}
 * </ul>
 *
 * @version 0.6.0
 */
public final class Docopt {

	/**
	 * long ::= '--' chars [ ( ' ' | '=' ) chars ] ;
	 */
	private static List<Option> parseLong(final Tokens tokens,
			final List<Option> options) {

		String $long;
		String eq;
		String value;

		// >>> long, eq, value = tokens.move().partition('=')
		{
			final String[] a = partition(tokens.move(), "=");
			$long = a[0];
			eq = a[1];
			value = a[2];
		}

		assert $long.startsWith("--");

		// >>> value = None if eq == value == '' else value
		if ("".equals(eq) && "".equals(value)) {
			value = null;
		}

		List<Option> similar;

		// >>> similar = [o for o in options if o.long == long]
		{
			similar = list();

			for (final Option o : options) {
				if ($long.equals(o.getLong())) {
					similar.add(o);
				}
			}
		}

		if (tokens.getError() == DocoptExitException.class && similar.isEmpty()) {
			// >>> similar = [o for o in options if o.long and
			// o.long.startswith(long)]
			{
				for (final Option o : options) {
					if (o.getLong() != null && o.getLong().startsWith($long)) {
						similar.add(o);
					}
				}
			}
		}

		if (similar.size() > 1) {
			List<String> u;

			// >>> o.long for o in similar
			{
				u = list();
				for (final Option o : similar) {
					u.add(o.getLong());
				}
			}

			throw tokens.error("%s is not a unique prefix: %s?", $long,
					join(", ", u));
		}

		Option o;

		if (similar.size() < 1) {
			final int argCount = "=".equals(eq) ? 1 : 0;

			o = new Option(null, $long, argCount);

			options.add(o);

			if (tokens.getError() == DocoptExitException.class) {
				// >>> o = Option(None, long, argcount, value if argcount else
				// True)
				o = new Option(null, $long, argCount, (argCount != 0) ? value
						: true);
			}
		}
		else {
			// >>> o = Option(similar[0].short, similar[0].long,
			// >>> similar[0].argcount, similar[0].value)
			{
				final Option u = similar.get(0);
				o = new Option(u.getShort(), u.getLong(), u.getArgCount(),
						u.getValue());
			}

			if (o.getArgCount() == 0) {
				if (value != null) {
					throw tokens.error("%s must not have an argument",
							o.getLong());
				}
			}
			else {
				if (value == null) {
					// >>> if tokens.current() in [None, '--']
					{
						final String u = tokens.current();
						if (u == null || "--".equals(u)) {
							throw tokens.error("%s requires argument",
									o.getLong());
						}
					}

					value = tokens.move();
				}
			}

			if (tokens.getError() == DocoptExitException.class) {
				o.setValue((value != null) ? value : true);
			}
		}

		return list(o);
	}

	/**
	 * shorts ::= '-' ( chars )* [ [ ' ' ] chars ] ;
	 */
	private static List<Option> parseShorts(final Tokens tokens,
			final List<Option> options) {
		final String token = tokens.move();
		assert token.startsWith("-") && !token.startsWith("--");
		String left = token.replaceFirst("^-+", "");

		final List<Option> parsed = list();

		while (!"".equals(left)) {
			final String $short = "-" + left.charAt(0);

			left = left.substring(1);

			List<Option> similar;

			// >>> similar = [o for o in options if o.short == short]
			{
				similar = list();

				for (final Option o : options) {
					if ($short.equals(o.getShort())) {
						similar.add(o);
					}
				}
			}

			if (similar.size() > 1) {
				throw tokens.error("%s is specified ambiguously %d times",
						$short, similar.size());
			}

			Option o;

			if (similar.size() < 1) {
				o = new Option($short, null, 0);

				options.add(o);

				if (tokens.getError() == DocoptExitException.class) {
					o = new Option($short, null, 0, true);
				}
			}
			else {
				// >>> o = Option(short, similar[0].long,
				// >>> similar[0].argcount, similar[0].value)
				{
					final Option u = similar.get(0);
					o = new Option($short, u.getLong(), u.getArgCount(),
							u.getValue());
				}

				String value = null;

				if (o.getArgCount() != 0) {
					if ("".equals(left)) {
						// >>> if tokens.current() in [None, '--']
						{
							final String u = tokens.current();
							if (u == null || "--".equals(u)) {
								throw tokens.error("%s requires argument",
										$short);
							}
							value = tokens.move();
						}
					}
					else {
						value = left;
						left = "";
					}
				}

				if (tokens.getError() == DocoptExitException.class) {
					o.setValue((value != null) ? value : true);
				}
			}

			parsed.add(o);
		}

		return parsed;
	}

	private static Required parsePattern(final String source,
			final List<Option> options) {
		final Tokens tokens = Tokens.fromPattern(source);
		final List<? extends Pattern> result = parseExpr(tokens, options);

		if (tokens.current() != null) {
			throw tokens.error("unexpected ending: %s", join(" ", tokens));
		}

		return new Required(result);
	}

	/**
	 * expr ::= seq ( '|' seq )* ;
	 */
	private static List<? extends Pattern> parseExpr(final Tokens tokens,
			final List<Option> options) {
		List<Pattern> seq = parseSeq(tokens, options);

		if (!"|".equals(tokens.current())) {
			return seq;
		}

		final List<Pattern> result = (seq.size() > 1) ? list((Pattern) new Required(
				seq))
				: seq;

		while ("|".equals(tokens.current())) {
			tokens.move();
			seq = parseSeq(tokens, options);
			result.addAll((seq.size() > 1) ? list(new Required(seq)) : seq);
		}

		return (result.size() > 1) ? list(new Either(result)) : result;
	}

	/**
	 * seq ::= ( atom [ '...' ] )* ;
	 */
	private static List<Pattern> parseSeq(final Tokens tokens,
			final List<Option> options) {
		final List<Pattern> result = list();

		// >>> while tokens.current() not in [None, ']', ')', '|']
		while (!in(tokens.current(), null, "]", ")", "|")) {
			List<? extends Pattern> atom = parseAtom(tokens, options);

			if ("...".equals(tokens.current())) {
				atom = list(new OneOrMore(atom));
				tokens.move();
			}

			result.addAll(atom);
		}

		return result;
	}

	/**
	 * atom ::= '(' expr ')' | '[' expr ']' | 'options' | long | shorts |
	 * argument | command ;
	 */
	private static List<? extends Pattern> parseAtom(final Tokens tokens,
			final List<Option> options) {
		final String token = tokens.current();

		List<Pattern> result = list();

		if ("(".equals(token) || "[".equals(token)) {
			tokens.move();

			String matching;

			// >>> matching, pattern = {'(': [')', Required], '[': [']',
			// Optional]}[token]
			// >>> result = pattern(*parse_expr(tokens, options))
			{
				final List<? extends Pattern> u = parseExpr(tokens, options);

				if ("(".equals(token)) {
					matching = ")";
					result = list((Pattern) new Required(u));
				}
				else if ("[".equals(token)) {
					matching = "]";
					result = list((Pattern) new Optional(u));
				}
				else {
					throw new IllegalStateException();
				}
			}

			if (!matching.equals(tokens.move())) {
				throw tokens.error("unmatched '%s'", token);
			}

			return list(result);
		}

		if ("options".equals(token)) {
			tokens.move();
			return list(new OptionsShortcut());
		}

		if (token.startsWith("--") && !"--".equals(token)) {
			return parseLong(tokens, options);
		}

		if (token.startsWith("-") && !("-".equals(token) || "--".equals(token))) {
			return parseShorts(tokens, options);
		}

		// >>> elif token.startswith('<') and token.endswith('>') or
		// token.isupper()
		if ((token.startsWith("<") && token.endsWith(">")) || isUpper(token)) {
			return list(new Argument(tokens.move()));
		}

		return list(new Command(tokens.move()));
	}

	/**
	 * Parse command-line argument vector.
	 *
	 * If options_first: argv ::= [ long | shorts ]* [ argument ]* [ '--' [
	 * argument ]* ] ; else: argv ::= [ long | shorts | argument ]* [ '--' [
	 * argument ]* ] ;
	 */
	private static List<LeafPattern> parseArgv(final Tokens tokens,
			final List<Option> options, final boolean optionsFirst) {
		final List<LeafPattern> parsed = list();

		while (tokens.current() != null) {
			if ("--".equals(tokens.current())) {
				// >>> return parsed + [Argument(None, v) for v in tokens]
				{
					for (final String v : tokens) {
						parsed.add(new Argument(null, v));
					}

					return parsed;
				}
			}

			if (tokens.current().startsWith("--")) { // TODO: Why don't we check
				// for tokens.current !=
				// "--" here?
				parsed.addAll(parseLong(tokens, options));
			}
			else if (tokens.current().startsWith("-")
					&& !"-".equals(tokens.current())) {
				parsed.addAll(parseShorts(tokens, options));
			}
			else if (optionsFirst) {
				// >>> return parsed + [Argument(None, v) for v in tokens]
				{
					for (final String v : tokens) {
						parsed.add(new Argument(null, v));
					}

					return parsed;
				}
			}
			else {
				parsed.add(new Argument(null, tokens.move()));
			}
		}

		return parsed;
	}

	private static List<Option> parseDefaults(final String doc) {
		final List<Option> defaults = list();

		for (String s : parseSection("options:", doc)) {
			// >>> u, u, s = s.partition(':') # get rid of "options:"
			{
				final String[] u = partition(s, ":");
				s = u[2];
			}

			List<String> split;

			// >>> split = re.split('\n *(-\S+?)', '\n' + s)[1:]
			{
				split = Re.split("\\n *(-\\S+?)", "\n" + s);
				split.remove(0);
			}

			// >>> split = [s1 + s2 for s1, s2 in zip(split[::2], split[1::2])];
			{
				final List<String> u = list();

				for (int i = 1; i < split.size(); i += 2) {
					u.add(split.get(i - 1) + split.get(i));
				}

				split = u;
			}

			// >>> options = [Option.parse(s) for s in split if
			// s.startswith('-')]
			// >>> defaults += options
			{
				for (final String $s : split) {
					if ($s.startsWith("-")) {
						defaults.add(Option.parse($s));
					}
				}
			}
		}

		return defaults;
	}

	private static List<String> parseSection(final String name,
			final String source) {
		// >>> return [s.strip() for s in pattern.findall(source)]
		{
			final List<String> u = Re.findAll("^([^\\n]*" + name +
					"[^\\n]*\\n?(?:[ \\t].*?(?:\\n|$))*)", source,
					Re.IGNORECASE | Re.MULTILINE);

			for (int i = 0; i < u.size(); i++) {
				u.set(i, u.get(i).trim());
			}

			return u;
		}
	}

	private static String formalUsage(String section) {
		// >>> u, u, section = section.partition(':')
		{
			final String[] u = partition(section, ":");
			section = u[2];
		}

		final List<String> pu = split(section);

		// >>> return '( ' + ' '.join(') | (' if s == pu[0] else s for s in
		// pu[1:]) + ' )'
		{
			final StringBuilder sb = new StringBuilder();

			sb.append("( ");

			final String u = pu.remove(0);

			if (!pu.isEmpty()) {
				for (final String s : pu) {
					if (s.equals(u)) {
						sb.append(") | (");
					}
					else {
						sb.append(s);
					}

					sb.append(" ");
				}

				sb.setLength(sb.length() - 1);
			}

			sb.append(" )");

			return sb.toString();
		}
	}

	private static void extras(final boolean help, final String version,
			final List<? extends LeafPattern> options, final String doc) {
		boolean u;

		// >>> if help and any((o.name in ('-h', '--help')) and o.value for o in
		// options)
		{
			u = false;

			if (help) {
				for (final LeafPattern o : options) {
					if ("-h".equals(o.getName()) | "--help".equals(o.getName())) {
						if (bool(o.getValue())) {
							u = true;
							break;
						}
					}
				}
			}
		}

		if (u) {
			throw new DocoptExitException(0, doc.replaceAll("^\\n+|\\n+$", ""),
					false);
		}

		// >>> if version and any(o.name == '--version' and o.value for o in
		// options)
		{
			u = false;

			if (bool(version)) {
				for (final LeafPattern o : options) {
					if ("--version".equals(o.getName())) {
						u = true;
						break;
					}
				}
			}
		}

		if (u) {
			throw new DocoptExitException(0, version, false);
		}
	}

	static String read(final InputStream stream, final String charset) {
		final Scanner scanner = new Scanner(stream, charset);

		try {
			scanner.useDelimiter("\\A");
			return scanner.hasNext() ? scanner.next() : "";
		}
		finally {
			scanner.close();
		}
	}

	static String read(final InputStream stream) {
		return read(stream, "UTF-8");
	}

	private final String doc;

	private final String usage;

	private final List<Option> options;

	private final Required pattern;

	private boolean help = true;

	private String version = null;

	private boolean optionsFirst = false;

	private boolean exit = true;

	private PrintStream out = System.out;

	private PrintStream err = System.err;

	/**
	 * Constructs an option parser from {@code doc}.
	 *
	 * @param doc
	 *            a POSIX-style help message
	 * @throws DocoptLanguageError
	 *             if {@code doc} is malformed
	 */
	public Docopt(final String doc) {
		this.doc = doc;

		final List<String> usageSections = parseSection("usage:", doc);

		if (usageSections.size() == 0) {
			throw new DocoptLanguageError(
					"\"usage:\" (case-insensitive) not found.");
		}

		if (usageSections.size() > 1) {
			throw new DocoptLanguageError(
					"More than one \"usage:\" (case-insensitive).");
		}

		usage = usageSections.get(0);
		options = parseDefaults(doc);
		pattern = parsePattern(formalUsage(usage), options);
	}

	/**
	 * Constructs an option parser from the contents of {@code doc}, read as a
	 * {@code charset} encoded string.
	 *
	 * @param doc
	 *            a stream containing a POSIX-style help message
	 * @param charset
	 *            the character encoding of the stream
	 * @throws DocoptLanguageError
	 *             if {@code doc} is malformed
	 */
	public Docopt(final InputStream doc, final Charset charset) {
		this(read(doc, charset.displayName()));
	}

	/**
	 * Constructs an option parser from the contents of {@code doc}, read as a
	 * UTF-8 string.
	 *
	 * @param doc
	 *            a stream containing a POSIX-style help message
	 * @throws DocoptLanguageError
	 *             if {@code doc} is malformed
	 */
	public Docopt(final InputStream doc) {
		this(read(doc));
	}

	public Docopt withHelp(final boolean help) {
		this.help = help;
		return this;
	}

	public Docopt withVersion(final String version) {
		this.version = version;
		return this;
	}

	public Docopt withVersion(final InputStream stream, final Charset charset) {
		this.version = read(stream, charset.displayName());
		return this;
	}

	public Docopt withVersion(final InputStream stream) {
		this.version = read(stream);
		return this;
	}

	public Docopt withOptionsFirst(final boolean optionsFirst) {
		this.optionsFirst = optionsFirst;
		return this;
	}

	public Docopt withExit(final boolean exit) {
		this.exit = exit;
		return this;
	}

	private Map<String, Object> doParse(final List<String> argv) {
		final List<LeafPattern> $argv = parseArgv(
				Tokens.withExitExcpetion(argv), list(options), optionsFirst);
		final Set<Pattern> patternOptions = set(pattern.flat(Option.class));

		for (final Pattern optionsShortcut : pattern
				.flat(OptionsShortcut.class)) {
			// >>> options_shortcut.children = list(set(doc_options) -
			// pattern_options)
			{
				final List<Pattern> u = ((BranchPattern) optionsShortcut)
						.getChildren();
				u.clear();
				u.addAll(set(options));
				Pattern o = null;
				for (final Iterator<Pattern> i = u.iterator(); i.hasNext();) {
					o = i.next();
					for (final Pattern x : patternOptions) {
						if (o.equals(x)) {
							i.remove();
						}
					}
				}
			}
		}

		extras(help, version, $argv, doc);

		final MatchResult m = pattern.fix().match($argv);

		if (m.matched() && m.getLeft().isEmpty()) {
			// >>> return Dict((a.name, a.value) for a in (pattern.flat() +
			// collected))
			final Map<String, Object> u = new HashMap<String, Object>();

			for (final Pattern p : pattern.flat()) {
				// TODO: Does flat always return LeafPattern objects?
				if (!(p instanceof LeafPattern)) {
					throw new IllegalStateException();
				}

				final LeafPattern lp = (LeafPattern) p;

				u.put(lp.getName(), lp.getValue());
			}

			for (final LeafPattern p : m.getCollected()) {
				u.put(p.getName(), p.getValue());
			}

			return u;
		}

		throw new DocoptExitException(1, null, true);
	}

	public Map<String, Object> parse(final List<String> argv) {
		try {
			return doParse(argv);
		}
		catch (final DocoptExitException e) {
			if (!exit) {
				throw e;
			}

			@SuppressWarnings("resource")
			final PrintStream ps = (e.getExitCode() == 0) ? out : err;

			if (ps != null) {
				final String message = e.getMessage();

				if (message != null) {
					ps.println(message);
				}

				if (e.getPrintUsage()) {
					ps.println(usage);
				}
			}

			System.exit(e.getExitCode());

			// Not reachable.
			throw new IllegalStateException();
		}
	}

	public Map<String, Object> parse(final String... argv) {
		return parse(Arrays.asList(argv));
	}

	Docopt withStdOut(final PrintStream out) {
		this.out = out;
		return this;
	}

	Docopt withStdErr(final PrintStream err) {
		this.err = err;
		return this;
	}
}
