package io.bit3.jsass.example.webapp;

import io.bit3.jsass.CompilationException;
import io.bit3.jsass.Compiler;
import io.bit3.jsass.Options;
import io.bit3.jsass.Output;
import io.bit3.jsass.importer.Import;
import org.apache.commons.io.IOUtils;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsassServlet extends HttpServlet {
	private final Pattern cssPattern = Pattern.compile("/(?<name>[^_]\\w+)\\.css");
	private Options options;

	@Override
	public void init() throws ServletException {
		options = new Options();
		options.setImporters(Collections.singleton(this::doImport));
	}

	@Override
	protected void doGet(
		final HttpServletRequest request,
		final HttpServletResponse response
	) throws ServletException, IOException {
		String pathInfo = request.getPathInfo();

		final Matcher matcher = cssPattern.matcher(pathInfo);
		if (!matcher.matches() || !deliverScss(matcher.group("name"), request, response)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, "Not found");
		}
	}

	private boolean deliverScss(
		final String fileName,
		final HttpServletRequest request,
		final HttpServletResponse response
	) throws ServletException, IOException {
		try {
			final String scssPath = String.format("/WEB-INF/scss/%s.scss", fileName);
			final URL scssResource = getServletContext().getResource(scssPath);

			if (null != scssResource) {
				final String scssCode = IOUtils.toString(scssResource, StandardCharsets.UTF_8);

				final Compiler compiler = new Compiler();
				final Output output = compiler.compileString(
					scssCode,
					new URI(scssPath),
					new URI(request.getRequestURI()),
					options
				);

				response.setContentType("text/css");
				response.getWriter().write(output.getCss());
				return true;
			}

			return false;
		} catch (CompilationException | URISyntaxException | MalformedURLException e) {
			throw new ServletException(e);
		}
	}

	private Collection<Import> doImport(String url, Import previous) {
		try {
			if (url.startsWith("/")) {
				// absolute imports are searched in /WEB-INF/scss
				return resolveImport(Paths.get("/WEB-INF/scss").resolve(url));
			}

			// find in import paths
			final List<Path> importPaths = new LinkedList<>();

			// (a) relative to the previous import file
			final String previousPath = previous.getAbsoluteUri().getPath();
			final Path previousParentPath = Paths.get(previousPath).getParent();
			importPaths.add(previousParentPath);

			// (b) within /WEB-INF/assets/foundation
			importPaths.add(Paths.get("/WEB-INF/assets/foundation"));

			// (c) within /WEB-INF/assets/motion-ui
			importPaths.add(Paths.get("/WEB-INF/assets/motion-ui"));

			for (Path importPath : importPaths) {
				Path target = importPath.resolve(url);

				Collection<Import> imports = resolveImport(target);
				if (null != imports) {
					return imports;
				}
			}

			// file not found
			throw new FileNotFoundException(url);
		} catch (URISyntaxException | IOException e) {
			throw new RuntimeException(e);
		}
	}

	private Collection<Import> resolveImport(Path path) throws IOException, URISyntaxException {
		URL resource = resolveResource(path);

		if (null == resource) {
			return null;
		}

		// calculate a webapp absolute URI
		final URI uri = new URI(
			Paths.get("/").resolve(
				Paths.get(getServletContext().getResource("/").toURI()).relativize(Paths.get(resource.toURI()))
			).toString()
		);
		final String source = IOUtils.toString(resource, StandardCharsets.UTF_8);

		final Import scssImport = new Import(uri, uri, source);
		return Collections.singleton(scssImport);
	}

	private URL resolveResource(Path path) throws IOException, URISyntaxException {
		final Path dir = path.getParent();
		final String basename = path.getFileName().toString();

		for (String prefix : new String[]{"_", ""}) {
			for (String suffix : new String[]{".scss", ".css", ""}) {
				final Path target = dir.resolve(prefix + basename + suffix);
				final URL resource = getServletContext().getResource(target.toString());

				if (null != resource) {
					return resource;
				}
			}
		}

		return null;
	}
}
