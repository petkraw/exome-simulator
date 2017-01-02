package de.charite.compbio.pediasimulator.cmd;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;

import de.charite.compbio.jannovar.annotation.AnnotationException;
import de.charite.compbio.jannovar.annotation.VariantEffect;
import de.charite.compbio.jannovar.htsjdk.InvalidCoordinatesException;
import de.charite.compbio.pediasimulator.cli.CommandLineParsingException;
import de.charite.compbio.pediasimulator.cli.JsonWithVCFExtenderOptions;
import de.charite.compbio.pediasimulator.filter.JannovarEffectInfoFilter;
import de.charite.compbio.pediasimulator.filter.JannovarGeneInfoFilter;
import de.charite.compbio.pediasimulator.io.OMIMGeneLoader;
import de.charite.compbio.pediasimulator.model.Sample;
import de.charite.compbio.pediasimulator.model.ScoreType;
import de.charite.compbio.pediasimulator.model.Variant;
import de.charite.compbio.pediasimulator.model.VariantsBuilder;
import de.charite.compbio.simdrom.filter.IFilter;
import de.charite.compbio.simdrom.filter.LessOrEqualInfoFieldFilter;
import de.charite.compbio.simdrom.sampler.vcf.VCFSampler;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFFileReader;
import net.sourceforge.argparse4j.inf.Namespace;

public class JsonWithVCFExtenderCommand implements ICommand {

	/** Configuration */
	private final String command;
	private final JsonWithVCFExtenderOptions options;
	private VariantsBuilder variantsBuilder;
	// private CADDScoreExtractor caddScoreExtractor;
	// private AnnotationFilter annotationFilter;

	private List<Sample> samples = new ArrayList<>();

	public JsonWithVCFExtenderCommand(String argv[], Namespace args) throws CommandLineParsingException {
		this.options = new JsonWithVCFExtenderOptions();
		this.options.setFromArgs(args);
		this.command = "java -jar pedia-simulator.jar " + Joiner.on(" ").join(argv);
	}

	@Override
	public void run() {

		// 1. Config parsers
		// 1.1 Init 1000G VCF file
		VCFFileReader vcfReader = new VCFFileReader(options.getVcfInputFile());

		// 1.2 load gene files from omim used for filtering
		OMIMGeneLoader omimGeneLoader = new OMIMGeneLoader(options.getOMIMFile());
		ImmutableSet<String> genes = omimGeneLoader.load();

		// 1.3 init filter
		ImmutableSet<IFilter> filters = new ImmutableSet.Builder<IFilter>()
				.add(new LessOrEqualInfoFieldFilter("EXAC_BEST_AF", 0.01))
				.add(new LessOrEqualInfoFieldFilter("UK10K_AF", 0.01)).add(new LessOrEqualInfoFieldFilter("AF", 0.01))
				.add(new LessOrEqualInfoFieldFilter("AFR_AF", 0.01)).add(new LessOrEqualInfoFieldFilter("AMR_AF", 0.01))
				.add(new LessOrEqualInfoFieldFilter("EAS_AF", 0.01)).add(new LessOrEqualInfoFieldFilter("EUR_AF", 0.01))
				.add(new LessOrEqualInfoFieldFilter("SAS_AF", 0.01)).add(new LessOrEqualInfoFieldFilter("SAS_AF", 0.01))
				.add(new JannovarEffectInfoFilter(VariantEffect._SMALLEST_MODERATE_IMPACT))
				.add(new JannovarGeneInfoFilter(genes)).build();

		// 1.4 ini sampler
		String sampleName = vcfReader.getFileHeader().getSampleNamesInOrder().get(0);
		VCFSampler sampler = new VCFSampler.Builder().vcfReader(vcfReader).filters(filters).sample(sampleName).build();

		// 2 create sample
		Sample sample = new Sample(sampleName);
		this.variantsBuilder = new VariantsBuilder.Builder().build();

		while (sampler.hasNext()) {
			VariantContext vc = sampler.next();
			if (vc.isSymbolic()) // skip symbolic variants
				continue;

			List<Variant> variants;
			try {
				variants = this.variantsBuilder.get(vc);

			} catch (AnnotationException | InvalidCoordinatesException e) {
				throw new RuntimeException("Cannot convert " + vc + " in variants", e);
			}

			sample.addAll(variants);
		}

		// 3 JSON
		// 3.1 read JSON
		JSONObject jsonObject;
		try (FileInputStream input = new FileInputStream(options.getJsonFile())) {
			JSONTokener tokener = new JSONTokener(input);
			jsonObject = (JSONObject) tokener.nextValue();

		} catch (IOException e) {
			throw new RuntimeException("Cannot read JSON file", e);
		}

		// 3.2 update existing json
		Set<String> usedGenes = new HashSet<>();
		JSONArray jsonGenes = (JSONArray) jsonObject.get("geneList");
		for (Object object : jsonGenes) {
			JSONObject jsonGene = (JSONObject) object;
			String gene = (String) jsonGene.get("gene_symbol");
			usedGenes.add(gene);
			if (sample.getScoresPerGene().containsKey(gene)) {
				OptionalDouble maxScore = sample.getScoresPerGene().get(gene).get(ScoreType.CADD).stream()
						.mapToDouble(s -> s).max();
				if (maxScore.isPresent())
					jsonGene.put("cadd_score", maxScore.getAsDouble());
			}
		}
		// 3.3 add new
		for (String gene : sample.getScoresPerGene().keySet()) {
			if (usedGenes.contains(gene))
				continue;

			OptionalDouble maxScore = sample.getScoresPerGene().get(gene).get(ScoreType.CADD).stream()
					.mapToDouble(s -> s).max();
			if (maxScore.isPresent()) {
				JSONObject jsonGene = new JSONObject();
				jsonGene.put("cadd_score", maxScore.getAsDouble());
				jsonGene.put("gene_symbol", gene);
				jsonGenes.put(jsonGene);
			}

		}
		jsonObject.append("cadd_extension:command", command);

		// 3.4 write new JSON
		try (FileOutputStream output = new FileOutputStream(options.getOutputFile())) {
			IOUtils.write(jsonObject.toString(), output);
		} catch (IOException e) {
			throw new RuntimeException("Cannot write JSON file", e);
		}

	}

}
